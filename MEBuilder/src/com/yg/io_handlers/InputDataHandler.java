package com.yg.io_handlers;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.yg.exceptions.InputParametersException;
import com.yg.models.MEInsertion;
import com.yg.models.BEDData;
import com.yg.models.Variants;
import com.yg.parsers.BEDParser;
import com.yg.parsers.VcfParser;
import com.yg.utilities.IOGeneralHelper;
import com.yg.utilities.PatternSplitter;
import com.yg.utilities.ProcessStream;

/**
 * This class submits files to parsers;
 * Checks quality of reads;
 * Collects discordant reads and split-reads and saves them to a FASTA file;
 * Collects concordant reads and saves them to a FASTA file.
 *
 * @author Yaroslava Girilishena
 *
 */
public class InputDataHandler {
	public final static Logger LOGGER = Logger.getLogger(Logger.GLOBAL_LOGGER_NAME); // init logger

	public VcfParser mainVcfParser; // vcf parser
	public String filePath; // file with locations

	// Quality values
	private Integer minQ = 0;
	private Double avgQ = 0.0;
	private Integer minL = 0;
	private Integer totalQ = 0;
	private Double qp = 0.0;
	private char[] ascii;

	private Map<String, Map<String, String>> sra2FA = new HashMap<String, Map<String, String>>();
	private Map<String, Integer> seenID = new HashMap<String, Integer>();

	// Process instances
	private Process process;
	private ProcessBuilder processBuilder;
	private ProcessStream errStream;
	private ProcessStream outputStream;
	private List<String> command; // command (list of parameters) to run SAMtools

	private List<String> samtoolsOutput; // output from SAMtools
	private List<String> data; // first read data
	private List<String> dataOther; // second read data
	private List<String> cigar; // CIGAR flag

	private int leftNumOfSplitReads = 0; // number of split-reads on the left side of insertion
	private int rightNumOfSplitReads = 0; // number of split-reads on the right side of insertion
	private int numOfDiscReads = 0; // number of discordant reads that cover insertion

	public InputDataHandler() {} // empty constructor

	/**
	 * Parse VCF file to collect chromosome and position of every event
	 * @param vcfFilePath
	 * @throws IOException
	 */
	public void parseVCF(String vcfFilePath) throws IOException {
		this.filePath = vcfFilePath;

		try {
			// Parse base VCF file with full data about events
			BufferedReader vcfBuffReader;
			vcfBuffReader = new BufferedReader(new FileReader(this.filePath));

			mainVcfParser = new VcfParser(vcfBuffReader);
			mainVcfParser.parse();

		} catch (IOException e) {
			throw new IOException(e);
		}
	}

	/**
	 * Parse BED file to collect data for every event
	 * @param bedFile
	 * @throws IOException
	 */
	public void parseBED(String bedFilePath) throws IOException {
		this.filePath = bedFilePath;

		try {
			// Parse base BED file
			BEDParser bedParser = new BEDParser(this.filePath, false);
			List<BEDData> nonRefMEIs = bedParser.parse();
			// Parse non-referenced MEIs data
			for (BEDData re : nonRefMEIs) {
				Variants.listOfMEI.add(new MEInsertion(re.getChrom(), re.getChromStart(), re.getChromEnd()));
			}
		} catch (IOException e) {
			throw new IOException(e);
		}
	}

	/**
	 * Get sequencing quality stats for each read's quality string
	 * @param qualStr
	 * @return
	 */
	private void getQualStats(String qualStr) {
		ascii = qualStr.toCharArray();
		minQ = 0;
		avgQ = 0.0;
		minL = 0;
		totalQ = 0;
		qp = 0.0;

		for (int i=0; i < ascii.length; i++) {
			int qual = (int) ascii[i] - 33;
			if (minQ == 0 || minQ > qual) {
				minQ = qual;
			}
			if (qual >= IOParameters.MIN_BASE_QUAL) {
				minL++;
				qp++;
			}
			totalQ += qual;
		}
		qp = qp / qualStr.length() * 100;
		avgQ = (double) (totalQ / qualStr.length());
	}

	/**
	 * Create reverse complementary sequence
	 * @param seq
	 * @return
	 */
	public static String reverseCompDNA(String seq) {
		String reverse = new StringBuilder(seq).reverse().toString();
		StringBuilder rcRes = new StringBuilder();

		for (int i=0; i < reverse.length(); i++) {
			rcRes.append(getComplementBP(reverse.charAt(i)));
		}

		return rcRes.toString();
	}
	
	/**
	 * Get complement base
	 * @param bp
	 * @return
	 */
	private static char getComplementBP(char bp) {
        switch (bp) {
	        case 'A':
	            return 'T';
	        case 'T':
	            return 'A';
	        case 'C':
	            return 'G';
	        case 'G':
	            return 'C';
	        case 'a':
	        	return 't';
	        case 't':
	        	return 'a';
	        case 'c':
	        	return 'g';
	        case 'g':
	        	return 'c';
        }
        return 'N';
    }

	/**
	 * Using SAMtools collect discordant reads and split-reads within flanking region
	 * Process all .bam files
	 * 
	 * @param chromosme
	 * @param position
	 * @throws InputParametersException
	 * @throws IOException
	 * @throws InterruptedException
	 */
	public Integer collectDiscordantReads(String chromosome, long position) throws InputParametersException, IOException, InterruptedException {
		if (IOParameters.LIST_OF_BAM_FILES.size() == 0) {
			// No BAM files found
			throw new InputParametersException("ERROR: - List of BAM files is empty");
		}

		// Setup start and end points of flanking region to search for reads
		long start = position - IOParameters.FLANKING_REGION;
		long end = position + IOParameters.FLANKING_REGION;

		// Create output directory if it doesn't exist
		IOGeneralHelper.createOutDir("/disc_reads");

		LOGGER.info("Processing " + chromosome + "_" + position + "\n");

		// Init variables
		sra2FA = new HashMap<String, Map<String, String>>();
		samtoolsOutput = new ArrayList<String>();
		seenID = new HashMap<String, Integer>();

		// Number of raw reads
		int raw = 0;
		// Number of qualified reads in each region to calculate the coverage
		Integer totReadLengthLeftRegion = 0;
		Integer totReadLengthRightRegion = 0;
		// Stats init
		leftNumOfSplitReads = 0;
		rightNumOfSplitReads = 0;
		numOfDiscReads = 0;

		// For all specified .bam files
		for (String bamfile : IOParameters.LIST_OF_BAM_FILES) {

			// ---------------------------------------------------------------
			// RUN SAMTOOLS
			// ---------------------------------------------------------------

			// Run SAM tools from command line

			// Commands for running SAM tools to collect discordant & split-reads reads
			command = new ArrayList<String>();
		    command.add(IOParameters.SAMTOOLS_PATH  + "samtools");
		    command.add("view");
		    command.add("-f 1"); // read paired (include only)
		    //command.add("-F 2"); // discordant reads - commented in order to collect split-reads (which are on a normal distance)
		    command.add("-F 4"); // unmapped read 1 (exclude)
		    command.add("-F 8"); // unmapped read 2 (exclude)
		    command.add("-F 256"); // non-primary matches (exclude)
		    command.add("-F 1024"); // secondary alignment (exclude)
		    command.add(bamfile.toString());
		    command.add(chromosome + ":" + start + "-" + end);

		    processBuilder = new ProcessBuilder(command);
            process = processBuilder.start();

            //LOGGER.info("processing " + bamfile.toString() + " for " +  chromosome + ":" + start + "-" + end);

			// Collect error messages
            errStream = new ProcessStream(process.getErrorStream(), "ERROR");
            errStream.start();

            // Catch error
            if (errStream.getOutput() != null && !errStream.getOutput().equals("") && errStream.getOutput().length() != 0) {
            	throw new InputParametersException("SAM TOOLS ERROR:\n" + errStream.getOutput());
            } else {
            	errStream.cleanBuffer();
            }

            // Collect output
            outputStream = new ProcessStream(process.getInputStream(), "OUTPUT");
            outputStream.start();

            process.waitFor();

            // Process SAM tools output
            if (outputStream.getOutput() == null || outputStream.getOutput().equals("")) {
            	continue;
            }

            samtoolsOutput = PatternSplitter.toList(PatternSplitter.PTRN_NEWLINE_SPLITTER, outputStream.getOutput().trim());
            outputStream.cleanBuffer();

			// For each output sequence
            // Example (consider in 1 line): SRR799394.13611051	65	chr1	10014390	60	90M11S	chr9	104466719	0	CCCAAGTAGCAGGGACTACAGGTGCATGACACCATGCCCAGCTAACTTCTTCTATTTTTTGTAGAGATGATGTCTCACCATGTTGCCCAGCATGGCAAAAG
            // @@?DFFDFDFFHHJJJJJJJGCABFFDHIIGGIIIJJJJIIIIIJGJJJJJJIHIGIEHIEHJIJJHHHGHHECEDBDDCEEADEEDCCA?CDDBBCCBDC	MD:Z:90	PG:Z:MarkDuplicates.S	NM:i:0	AS:i:90	XS:i:39
            for (String samLine : samtoolsOutput) {

            	raw++; // count the number of reads

            	// Get data for each read - SAM line for reads in the flanking region of predicted MEI
            	data = PatternSplitter.toList(PatternSplitter.PTRN_TAB_SPLITTER, samLine);

            	// Exclude random chromosome
            	Matcher matcher = Pattern.compile("^chr[0-9XY]+$").matcher(data.get(6));
                if (IOParameters.EXCLUDE_RANDOM && !matcher.find()) {
                	continue;
                }

            	// Check for repeated reads
            	if (seenID.containsKey(data.get(0)) && seenID.get(data.get(0)) != 0) {
            		continue;
            	} else {
            		// Save read ID
            		seenID.put(data.get(0), 1);
            	}

                // Skip poor sequences
            	matcher = Pattern.compile("[Nn]{4,}").matcher(data.get(9));
            	if (matcher.find() || data.get(9).length() < IOParameters.MIN_READ_LENGTH) {
            		//LOGGER.info("SKIPPED - first read bad base quality");
            		continue;
            	}


	            // Check quality if requested
            	if (IOParameters.CHECK_QUAL) {
	            	getQualStats(data.get(10));
	            	// Filter using quality matrix
	            	if (minQ < IOParameters.MIN_BASE_QUAL ||
	            		avgQ < IOParameters.MIN_AVG_READ_QUAL ||
	            		minL < IOParameters.MIN_NUM_OF_BASES_ABOVE_QUAL ||
	            		qp < IOParameters.PERCENT_BASE_ABOVE_QUAL) {
	            		//LOGGER.info("SKIPPED - first read bad quality");
	            		continue;
	            	}
            	}

            	int xaIndex = -1;
	            int xsIndex = -1;
	            List<String> xaAlignments = new ArrayList<String>();
	            List<String> xsAlignments = new ArrayList<String>();
	            
            	if (!PatternSplitter.SOFT_CLIPPED_5_MS.matcher(data.get(5)).find() && !PatternSplitter.SOFT_CLIPPED_3_SM.matcher(data.get(5)).find()) {

	            	// Get indexes of 'XA' and 'XS' flags
	            	xaIndex = -1;
		            xsIndex = -1;
		            xaAlignments = new ArrayList<String>();
		            xsAlignments = new ArrayList<String>();
	
		            for (int idx = 11; idx < data.size(); idx++) {
		            	if (data.get(idx).startsWith("XA")) {
		            		xaIndex = idx;
		            		// Collect alternative alignments
		            		xaAlignments = PatternSplitter.toList(PatternSplitter.PTRN_SEMICOLON_SPLITTER, data.get(xaIndex));
		            	} else if (data.get(idx).startsWith("XS")) {
		            		xsIndex = idx;
		            		// Collect suboptimal alignments
		            		xsAlignments = PatternSplitter.toList(PatternSplitter.PTRN_COLON_SPLITTER, data.get(xsIndex));
		            	}
		            }
	
		            // If 'XA' flag doesn't exist or number of alternative alignments < 2 && 'XS' doesn't exist or suboptimal alignments < 5 - skip read pair
		            if ( (xaIndex == -1 || xaAlignments.size() < 2) &&
		            	 (xsIndex == -1 || xsAlignments.size() == 0 || Integer.parseInt(xsAlignments.get(xsAlignments.size()-1)) < 5) ) {
		            	continue;
		            }
		            xaAlignments.clear();
		            xsAlignments.clear();
            	}

            	// Second read mapped to the same chromosome
            	if (data.get(6).equals("=")) {
            		data.set(6, data.get(2));
            	}

                // Retrieve the other read from the BAM file

                // Commands for running samtools to get other read
                command.clear();
                command.add(IOParameters.SAMTOOLS_PATH  + "samtools");
                command.add("view");
                command.add("-f 1"); // read paired
                command.add("-F 4"); // unmapped read 1
                command.add("-F 8"); // unmapped read 2
                command.add("-F 256"); // non-primary matches
                command.add("-F 1024"); // secondary alignment
                command.add(bamfile.toString());
                command.add(data.get(6) + ":" + data.get(7) + "-" + data.get(7));

                processBuilder = new ProcessBuilder(command);
                process = processBuilder.start();

                // Collect error messages
                errStream = new ProcessStream(process.getErrorStream(), "ERROR");
                errStream.start();

                // Catch error
                if (errStream.getOutput() != null && !errStream.getOutput().equals("") && errStream.getOutput().length() != 0) {
                	throw new InputParametersException("SAM TOOLS 2 ERROR:\n" + errStream.getOutput());
                } else {
                	errStream.cleanBuffer();
                }

                // Collect output
                outputStream = new ProcessStream(process.getInputStream(), "OUTPUT");
                outputStream.start();

	            process.waitFor();

	            // Process to get the read with the same sequence ID
	            dataOther = null;
	            String samLine2 = "";
	            List<String> otherReads = PatternSplitter.toList(PatternSplitter.PTRN_NEWLINE_SPLITTER, outputStream.getOutput().trim());
	            for (String otherReadLine : otherReads) {
	            	dataOther = PatternSplitter.toList(PatternSplitter.PTRN_TAB_SPLITTER, otherReadLine);
	            	if (dataOther.get(0).equals(data.get(0)) && Long.parseLong(data.get(7)) == Long.parseLong(dataOther.get(3))) {
	            		samLine2 = otherReadLine;
	            		break;
	            	}
	            	dataOther = null;
	            }
	            otherReads.clear();
	            outputStream.cleanBuffer();

	            // No second read found
	            if (dataOther == null || dataOther.size() == 0) {
	            	continue;
	            }

	            // Skip poor sequences
	            matcher = Pattern.compile("[Nn]{4,}").matcher(dataOther.get(9));
            	if (matcher.find() || dataOther.get(9).length() < IOParameters.MIN_READ_LENGTH) {
            		continue;
            	}

            	// Check quality if requested
            	if (IOParameters.CHECK_QUAL) {
            		getQualStats(dataOther.get(10));
	            	// Filter using quality matrix
	            	if (minQ < IOParameters.MIN_BASE_QUAL ||
	            		avgQ < IOParameters.MIN_AVG_READ_QUAL ||
	            		minL < IOParameters.MIN_NUM_OF_BASES_ABOVE_QUAL ||
	            		qp < IOParameters.PERCENT_BASE_ABOVE_QUAL) {
	            		continue;
	            	}
            	}

	            // Filter out reads that are not part of MEI (for non-anchoring)
	            // Example of alternative alignments: XA:Z:chr14,+72141313,80M20S,5;chr10,-65036133,20S80M,7;chr2,-208658288,38S58M4S,2;chr11,+73350796,47M53S,1;
	            // Example of suboptimal alignments: XS:i:59

            	if (!PatternSplitter.SOFT_CLIPPED_5_MS.matcher(dataOther.get(5)).find() && !PatternSplitter.SOFT_CLIPPED_3_SM.matcher(dataOther.get(5)).find()) {
		            // Get indexes of 'XA' and 'XS' flags
	            	xaIndex = -1;
		            xsIndex = -1;
		            xaAlignments = new ArrayList<String>();
		            xsAlignments = new ArrayList<String>();
	
		            for (int idx = 11; idx < dataOther.size(); idx++) {
		            	if (dataOther.get(idx).startsWith("XA")) {
		            		xaIndex = idx;
		            		// Collect alternative alignments
		            		xaAlignments = PatternSplitter.toList(PatternSplitter.PTRN_SEMICOLON_SPLITTER, dataOther.get(xaIndex));
		            	} else if (dataOther.get(idx).startsWith("XS")) {
		            		xsIndex = idx;
		            		// Collect suboptimal alignments
		            		xsAlignments = PatternSplitter.toList(PatternSplitter.PTRN_COLON_SPLITTER, dataOther.get(xsIndex));
		            	}
		            }
	
		            // If 'XA' flag doesn't exist or number of alternative alignments < 2 && 'XS' doesn't exist or suboptimal alignments < 5 - skip read pair
		            if ( (xaIndex == -1 || xaAlignments.size() < 2) &&
		            	 (xsIndex == -1 || xsAlignments.size() == 0 || Integer.parseInt(xsAlignments.get(xsAlignments.size()-1)) < 5) ) {
		            	continue;
		            }
		            xaAlignments.clear();
		            xsAlignments.clear();
            	}
		            
	            
	            // ---------------------------------------------
	            // FULLY CONCORDANT - FILTER OUT
	            // ---------------------------------------------

	            // Filter out fully concordant reads
	            if ((Integer.parseInt(data.get(1)) & 2) > 0) { // read mapped in proper pair
	            	Matcher softR1 = Pattern.compile("\\d+S").matcher(data.get(5));
	            	Matcher softR2 = Pattern.compile("\\d+S").matcher(dataOther.get(5));

	            	// the entire pair is before the insertion
	            	if (Integer.parseInt(data.get(3)) + data.get(9).length() <= position && Integer.parseInt(dataOther.get(3)) + dataOther.get(9).length() <= position &&
	            			!softR1.find() && !softR2.find()) {
	            		continue;
	            	}
	            	// the entire pair is after the insertion
	            	if (Integer.parseInt(data.get(3)) > position && Integer.parseInt(dataOther.get(3)) > position &&
	            			!softR1.find() && !softR2.find()) {
	            		continue;
	            	}

	            	// pair reads flanking the insertion site with no soft clipping
	            	// for allowing insertion aneil for shorter insertion > 200
	            	if (Integer.parseInt(data.get(3)) < position && Integer.parseInt(dataOther.get(3)) > position &&
	            			Integer.parseInt(data.get(8)) >= 200 && !softR1.find() && !softR2.find()) {
	            		continue;
	            	}
	            	if (Integer.parseInt(data.get(3)) > position && Integer.parseInt(dataOther.get(3)) < position &&
	            			Integer.parseInt(data.get(8)) <= -200 && !softR1.find() && !softR2.find()) {
	            		continue;
	            	}
	            }
	            
	            if (IOParameters.FILTERS_APPLY) {
		            	
		            boolean qualifiedPair = false;
		            // String.matches returns whether the whole string matches the regex, not just any substring
		            // Allow 1-5bp around position to be matched in soft clipped reads, e.g. chr10	9510991	60	20S81M
	
		            // ---------------------------------------------
		            // BOTH READS IN 5' - SPLIT-READS
		            // ---------------------------------------------
		            // +2 to position to allow some flexibility
	
		            // Concordant pairs with 2nd split at 5' and 1st match in 5'
		            if (Integer.parseInt(data.get(3)) < position && Integer.parseInt(data.get(3)) >= start &&
		            		Integer.parseInt(dataOther.get(3)) < position + 5 && Integer.parseInt(dataOther.get(3)) >= start &&
		            		PatternSplitter.SOFT_CLIPPED_5_MS.matcher(dataOther.get(5)).find() && PatternSplitter.FULL_MATCH_M.matcher(data.get(5)).find()) {
		            	// Get CIGAR
		            	cigar = PatternSplitter.toList(PatternSplitter.PTRN_NUMBERS_LETTERS_SPLITTER, dataOther.get(5));
	
		            	// Check 'S' value
		            	if (dataOther.get(5).indexOf('S') == -1 || cigar.indexOf("S") != (cigar.size() - 1)) {
		            		continue;
		            	}
		            	if (dataOther.get(5).indexOf('S') != -1 && Integer.parseInt(cigar.get(cigar.indexOf("S") - 1)) < 10) {
		            		continue;
		            	}
		            	// Check 'M' value
		            	if (dataOther.get(5).indexOf('M') == -1 || cigar.indexOf("M") != 1) {
		            		continue;
		            	}
		            	// Skip reads that are mapped before the position or exceed the position too much
		            	int matchesAligned = Integer.parseInt(cigar.get(cigar.indexOf("M") - 1)) + Integer.parseInt(dataOther.get(3));
		            	if (dataOther.get(5).indexOf('M') != -1 && (matchesAligned < position - 10 || matchesAligned > position + 10)) {
		            		continue;
		            	}
	
		            	leftNumOfSplitReads++; // increase the number of split-reads in the left region
		            	qualifiedPair = true;
		            }
	
		            // Concordant pairs with 1st read split at 5' side of the insertion point, 2nd in 5'
		            if (Integer.parseInt(data.get(3)) < position + 5 && Integer.parseInt(data.get(3)) >= start &&
		            		Integer.parseInt(dataOther.get(3)) < position && Integer.parseInt(dataOther.get(3)) >= start &&
		            		//(Integer.parseInt(data.get(1)) & 2) > 0 &&
		            		PatternSplitter.SOFT_CLIPPED_5_MS.matcher(data.get(5)).find() && PatternSplitter.FULL_MATCH_M.matcher(dataOther.get(5)).find()) {
	
		            	// Get CIGAR
		            	cigar = PatternSplitter.toList(PatternSplitter.PTRN_NUMBERS_LETTERS_SPLITTER, data.get(5));
	
		            	// Check 'S' value
		            	if (data.get(5).indexOf('S') == -1 || cigar.indexOf("S") != (cigar.size() - 1)) {
		            		continue;
		            	}
		            	if (data.get(5).indexOf('S') != -1 && Integer.parseInt(cigar.get(cigar.indexOf("S") - 1)) < 10) {
		            		continue;
		            	}
		            	// Check 'M' value
		            	if (data.get(5).indexOf('M') == -1 || cigar.indexOf("M") != 1) {
		            		continue;
		            	}
		            	// Skip reads that are mapped before the position or exceed the position too much
		            	int matchesAligned = Integer.parseInt(cigar.get(cigar.indexOf("M") - 1)) + Integer.parseInt(data.get(3));
		            	if (data.get(5).indexOf('M') != -1 && (matchesAligned < position-10 || matchesAligned > position + 10)) {
		            		continue;
		            	}
	
		            	leftNumOfSplitReads++; // increase the number of split-reads in the left region
		            	qualifiedPair = true;
		            }
	
		            // ---------------------------------------------
		            // BOTH READS IN 3' - SPLIT-READS
		            // ---------------------------------------------
	
		            // Example (consider in 1 line): SRR799394.13611051	65	chr1	10014390	60	90M11S	chr9	104466719	0	CCCAAGTAGCAGGGACTACAGGTGCATGACACCATGCCCAGCTAACTTCTTCTATTTTTTGTAGAGATGATGTCTCACCATGTTGCCCAGCATGGCAAAAG
		            // @@?DFFDFDFFHHJJJJJJJGCABFFDHIIGGIIIJJJJIIIIIJGJJJJJJIHIGIEHIEHJIJJHHHGHHECEDBDDCEEADEEDCCA?CDDBBCCBDC	MD:Z:90	PG:Z:MarkDuplicates.S	NM:i:0	AS:i:90	XS:i:39
	
		            // Concordant pairs with the 1st read split at 3' side of the insertion point, 2nd in 3'
		            if (Integer.parseInt(data.get(3)) > position - 100 && Integer.parseInt(data.get(3)) < end &&
		            		Integer.parseInt(dataOther.get(3)) > position && Integer.parseInt(dataOther.get(3)) + dataOther.get(9).length() <= end &&
		            		(Integer.parseInt(data.get(1)) & 16) <= 0 &&
		            		PatternSplitter.SOFT_CLIPPED_3_SM.matcher(data.get(5)).find() && PatternSplitter.FULL_MATCH_M.matcher(dataOther.get(5)).find()) {
		            	// Check 'S' value
		            	cigar = PatternSplitter.toList(PatternSplitter.PTRN_NUMBERS_LETTERS_SPLITTER, data.get(5));
		            	if (data.get(5).indexOf('S') == -1 || cigar.indexOf("S") != 1) {
		            		continue;
		            	}
		            	if (data.get(5).indexOf('S') != -1 && Integer.parseInt(cigar.get(cigar.indexOf("S") - 1)) < 10) {
		            		continue;
		            	}
	
		            	rightNumOfSplitReads++; // increase the number of split-reads in the right region
		            	qualifiedPair = true;
		            }
	
		            // Concordant pairs with the 2nd read split at 3' side of the insertion point, 1st in 3'
		            if (Integer.parseInt(dataOther.get(3)) > position - 100 && Integer.parseInt(dataOther.get(3)) < end &&
		            		Integer.parseInt(data.get(3)) > position && Integer.parseInt(data.get(3)) + data.get(9).length() <= end &&
		            		(Integer.parseInt(data.get(1)) & 16) > 0 &&
		            		PatternSplitter.SOFT_CLIPPED_3_SM.matcher(dataOther.get(5)).find() && PatternSplitter.FULL_MATCH_M.matcher(data.get(5)).find()) {
		            	// Check 'S' value
		            	cigar = PatternSplitter.toList(PatternSplitter.PTRN_NUMBERS_LETTERS_SPLITTER, dataOther.get(5));
		            	if (dataOther.get(5).indexOf('S') == -1 || cigar.indexOf("S") != 1) {
		            		continue;
		            	}
		            	if (dataOther.get(5).indexOf('S') != -1 && Integer.parseInt(cigar.get(cigar.indexOf("S") - 1)) < 10) {
		            		continue;
		            	}
	
		            	rightNumOfSplitReads++; // increase the number of split-reads in the right region
		            	qualifiedPair = true;
		            }
	
		            // ---------------------------------------------
		            // SPLIT IN 5' MATE IN 3' - SPLIT-READS
		            // ---------------------------------------------
	
		            // Concordant pairs with the 1st read as 5' split read and 2nd in 3'
		            if (Integer.parseInt(data.get(3)) >= start && Integer.parseInt(data.get(3)) < position + 5 &&
		    	            Integer.parseInt(dataOther.get(3)) > position && Integer.parseInt(dataOther.get(3)) + dataOther.get(9).length() <= end &&
		    	            PatternSplitter.SOFT_CLIPPED_5_MS.matcher(data.get(5)).find() && PatternSplitter.FULL_MATCH_M.matcher(dataOther.get(5)).find()) {
		            	
		            	cigar = PatternSplitter.toList(PatternSplitter.PTRN_NUMBERS_LETTERS_SPLITTER, data.get(5));
		            	// Check 'S' value
		            	if (data.get(5).indexOf('S') == -1 || cigar.indexOf("S") != (cigar.size() - 1)) {
		            		continue;
		            	}
		            	if (data.get(5).indexOf('S') != -1 && Integer.parseInt(cigar.get(cigar.indexOf("S") - 1)) < 10) {
		            		continue;
		            	}
		            	
		            	// Check 'M' value
		            	if (data.get(5).indexOf('M') == -1 || cigar.indexOf("M") != 1) {
		            		continue;
		            	}
		            	// Skip reads that are mapped before the position or exceed the position too much
		            	int matchesAligned = Integer.parseInt(cigar.get(cigar.indexOf("M") - 1)) + Integer.parseInt(data.get(3));
		            	if (data.get(5).indexOf('M') != -1 && (matchesAligned < position - 10 || matchesAligned > position + 10)) {
		            		continue;
		            	}
	
		            	leftNumOfSplitReads++; // increase the number of split-reads in the left region
		            	qualifiedPair = true;
		            }
	
		            // Concordant pairs with the 2nd read as 5' split read and 1st in 3'
		            if (Integer.parseInt(dataOther.get(3)) >= start && Integer.parseInt(dataOther.get(3)) < position + 5 &&
		    	            Integer.parseInt(data.get(3)) > position && Integer.parseInt(data.get(3)) + data.get(9).length() <= end &&
		    	            PatternSplitter.SOFT_CLIPPED_5_MS.matcher(dataOther.get(5)).find() && PatternSplitter.FULL_MATCH_M.matcher(data.get(5)).find()) {
		            	
		            	cigar = PatternSplitter.toList(PatternSplitter.PTRN_NUMBERS_LETTERS_SPLITTER, dataOther.get(5));
		            	// Check 'S' value
		            	if (dataOther.get(5).indexOf('S') == -1 || cigar.indexOf("S") != (cigar.size() - 1)) {
		            		continue;
		            	}
		            	if (dataOther.get(5).indexOf('S') != -1 && Integer.parseInt(cigar.get(cigar.indexOf("S") - 1)) < 10) {
		            		continue;
		            	}
	
		            	// Check 'M' value
		            	if (dataOther.get(5).indexOf('M') == -1 || cigar.indexOf("M") != 1) {
		            		continue;
		            	}
		            	// Skip reads that are mapped before the position or exceed the position too much
		            	int matchesAligned = Integer.parseInt(cigar.get(cigar.indexOf("M") - 1)) + Integer.parseInt(dataOther.get(3));
		            	if (dataOther.get(5).indexOf('M') != -1 && (matchesAligned < position - 10 || matchesAligned > position + 10)) {
		            		continue;
		            	}
		            	
		            	leftNumOfSplitReads++; // increase the number of split-reads in the left region
		            	qualifiedPair = true;
		            }
	
		            // ---------------------------------------------
		            // SPLIT IN 3' MATE IN 5' - SPLIT-READS
		            // ---------------------------------------------
	
		            // Concordant pairs with the 1st read as 3' split read and 2nd in 5'
		            if (Integer.parseInt(data.get(3)) > position - 100 && Integer.parseInt(data.get(3)) <= end &&
		    	            Integer.parseInt(dataOther.get(3)) < position && Integer.parseInt(dataOther.get(3)) >= start &&
		    	            PatternSplitter.SOFT_CLIPPED_3_SM.matcher(data.get(5)).find() && PatternSplitter.FULL_MATCH_M.matcher(dataOther.get(5)).find()) {
		            	// Check 'S' value
		            	cigar = PatternSplitter.toList(PatternSplitter.PTRN_NUMBERS_LETTERS_SPLITTER, data.get(5));
		            	if (data.get(5).indexOf('S') == -1 || cigar.indexOf("S") != 1) {
		            		continue;
		            	}
		            	if (data.get(5).indexOf('S') != -1 && Integer.parseInt(cigar.get(cigar.indexOf("S") - 1)) < 10) {
		            		continue;
		            	}
	
		            	rightNumOfSplitReads++; // increase the number of split-reads in the right region
		            	qualifiedPair = true;
		            }
	
		            // Concordant pairs with the 2nd read as 3' split read and 1st in 5'
		            if (Integer.parseInt(dataOther.get(3)) > position - 100 && Integer.parseInt(dataOther.get(3)) <= end &&
		    	            Integer.parseInt(data.get(3)) < position && Integer.parseInt(data.get(3)) >= start &&
		    	            PatternSplitter.SOFT_CLIPPED_3_SM.matcher(dataOther.get(5)).find() && PatternSplitter.FULL_MATCH_M.matcher(data.get(5)).find()) {
		            	// Check 'S' value
		            	cigar = PatternSplitter.toList(PatternSplitter.PTRN_NUMBERS_LETTERS_SPLITTER, dataOther.get(5));
		            	if (dataOther.get(5).indexOf('S') == -1 || cigar.indexOf("S") != 1) {
		            		continue;
		            	}
		            	if (dataOther.get(5).indexOf('S') != -1 && Integer.parseInt(cigar.get(cigar.indexOf("S") - 1)) < 10) {
		            		continue;
		            	}
	
		            	rightNumOfSplitReads++; // increase the number of split-reads in the right region
		            	qualifiedPair = true;
		            }
	
		            // ---------------------------------------------
		            // ONE SPLIT - DISCORDANT
		            // ---------------------------------------------
	
		            // Pairs with the 1st read as 5' split read and mate inside the insertion
		            if (Integer.parseInt(data.get(3)) < position + 5 && Integer.parseInt(data.get(3)) >= start &&
		            		PatternSplitter.SOFT_CLIPPED_5_MS.matcher(data.get(5)).find() && //PatternSplitter.FULL_MATCH_M.matcher(dataOther.get(5)).find() &&
		            		(!data.get(2).equals(dataOther.get(2)) || (data.get(2).equals(dataOther.get(2)) && Math.abs(Integer.parseInt(data.get(3)) - Integer.parseInt(dataOther.get(3))) > 1000000)) &&
		            		(Integer.parseInt(data.get(1)) & 2) <= 0) {
		            	
		            	cigar = PatternSplitter.toList(PatternSplitter.PTRN_NUMBERS_LETTERS_SPLITTER, data.get(5));
		            	
		            	// Check 'M' value
		            	if (data.get(5).indexOf('M') == -1 || cigar.indexOf("M") != 1) {
		            		continue;
		            	}
		            	int matchesAligned = Integer.parseInt(cigar.get(cigar.indexOf("M") - 1)) + Integer.parseInt(data.get(3));
		            	if (data.get(5).indexOf('M') != -1 && (matchesAligned < position - 10 || matchesAligned > (position + 10))) {
		            		continue;
		            	}
		            	
		            	// Check 'S' value
		            	cigar = PatternSplitter.toList(PatternSplitter.PTRN_NUMBERS_LETTERS_SPLITTER, data.get(5));
		            	if (data.get(5).indexOf('S') == -1 || cigar.indexOf("S") != cigar.size()-1) {
		            		continue;
		            	}
		            	if (data.get(5).indexOf('S') != -1 && Integer.parseInt(cigar.get(cigar.indexOf("S") - 1)) < 10) {
		            		continue;
		            	}
	
		            	leftNumOfSplitReads++; // increase the number of split-reads in the left region
		            	qualifiedPair = true;
		            }
	
		            // Pairs with the 1st read as 3' split read flank and mate in the insertion
		            if (Integer.parseInt(data.get(3)) > position - 100 && Integer.parseInt(data.get(3)) < end &&
		            		PatternSplitter.SOFT_CLIPPED_3_SM.matcher(data.get(5)).find() && //PatternSplitter.FULL_MATCH_M.matcher(dataOther.get(5)).find() &&
		            		(!data.get(2).equals(dataOther.get(2)) || (data.get(2).equals(dataOther.get(2)) && Math.abs(Integer.parseInt(data.get(3)) - Integer.parseInt(dataOther.get(3))) > 1000000)) &&
		            		(Integer.parseInt(data.get(1)) & 2) <= 0) {
		            	// Check 'S' value
		            	cigar = PatternSplitter.toList(PatternSplitter.PTRN_NUMBERS_LETTERS_SPLITTER, data.get(5));
		            	if (data.get(5).indexOf('S') == -1 || cigar.indexOf("S") != 1) {
		            		continue;
		            	}
		            	if (data.get(5).indexOf('S') != -1 && Integer.parseInt(cigar.get(cigar.indexOf("S") - 1)) < 10) {
		            		continue;
		            	}
	
		            	rightNumOfSplitReads++; // increase the number of split-reads in the right region
		            	qualifiedPair = true;
		            }
	
		            // ---------------------------------------------
		            // BOTH SPLIT READS
		            // ---------------------------------------------
	
		            // 1st split in 5', 2nd in 3'
		            if (Integer.parseInt(data.get(3)) <= position + 5 && Integer.parseInt(data.get(3)) >= start &&
		            		Integer.parseInt(dataOther.get(3)) <= position + 5 && Integer.parseInt(dataOther.get(3)) >= start &&
		            		PatternSplitter.SOFT_CLIPPED_5_MS.matcher(data.get(5)).find() &&
		            		PatternSplitter.SOFT_CLIPPED_3_SM.matcher(dataOther.get(5)).find()) {
		            	// Check 'S' value for the first read
		            	cigar = PatternSplitter.toList(PatternSplitter.PTRN_NUMBERS_LETTERS_SPLITTER, data.get(5));
		            	if (data.get(5).indexOf('S') == -1 || cigar.indexOf("S") != cigar.size()-1) {
		            		continue;
		            	}
		            	int sValueInR1 = Integer.parseInt(cigar.get(cigar.indexOf("S") - 1));
	
		            	// Check 'S' value for the second read
		            	cigar = PatternSplitter.toList(PatternSplitter.PTRN_NUMBERS_LETTERS_SPLITTER, dataOther.get(5));
		            	if (dataOther.get(5).indexOf('S') == -1 || cigar.indexOf("S") != 1) {
		            		continue;
		            	}
		            	if (dataOther.get(5).indexOf('S') != -1 && (sValueInR1 < 10 && Integer.parseInt(cigar.get(cigar.indexOf("S") - 1)) < 10)) {
		            		continue;
		            	}
	
		            	leftNumOfSplitReads++; // increase the number of split-reads in the left region
		            	rightNumOfSplitReads++; // increase the number of split-reads in the right region
		            	qualifiedPair = true;
		            }
	
		            // 2nd split in 5', 1st in 3'
		            if (Integer.parseInt(data.get(3)) <= position+5 && Integer.parseInt(data.get(3)) >= start &&
		            		Integer.parseInt(dataOther.get(3)) <= position+5 && Integer.parseInt(dataOther.get(3)) >= start &&
		            		PatternSplitter.SOFT_CLIPPED_5_MS.matcher(dataOther.get(5)).find() &&
		            		PatternSplitter.SOFT_CLIPPED_3_SM.matcher(data.get(5)).find()) {
		            	// Check 'S' value for the first read
		            	cigar = PatternSplitter.toList(PatternSplitter.PTRN_NUMBERS_LETTERS_SPLITTER, data.get(5));
		            	if (data.get(5).indexOf('S') == -1 || cigar.indexOf("S") != 1) {
		            		continue;
		            	}
		            	int sValueInR1 = Integer.parseInt(cigar.get(cigar.indexOf("S") - 1));
	
		            	// Check 'S' value for the second read
		            	cigar = PatternSplitter.toList(PatternSplitter.PTRN_NUMBERS_LETTERS_SPLITTER, dataOther.get(5));
		            	if (dataOther.get(5).indexOf('S') == -1 || cigar.indexOf("S") != cigar.size()-1) {
		            		continue;
		            	}
		            	if (dataOther.get(5).indexOf('S') != -1 && (sValueInR1 < 10 && Integer.parseInt(cigar.get(cigar.indexOf("S") - 1)) < 10)) {
		            		continue;
		            	}
	
		            	leftNumOfSplitReads++; // increase the number of split-reads in the left region
		            	rightNumOfSplitReads++; // increase the number of split-reads in the right region
		            	qualifiedPair = true;
		            }
	
		            // ---------------------------------------------
		            // JUST DISCORDANT
		            // ---------------------------------------------
	
		            // Discordant pairs with the 1st read within 5' flank and mate inside the insertion
		            if (Integer.parseInt(data.get(3)) >= start && Integer.parseInt(data.get(3)) + data.get(9).length() <= position &&
		            		(Integer.parseInt(data.get(1)) & 16) <= 0 &&
		            		PatternSplitter.FULL_MATCH_M.matcher(data.get(5)).find() && //PatternSplitter.FULL_MATCH_M.matcher(dataOther.get(5)).find() &&
		            		(!data.get(2).equals(dataOther.get(2)) || (data.get(2).equals(dataOther.get(2)) && Math.abs(Integer.parseInt(data.get(3)) - Integer.parseInt(dataOther.get(3))) > 1000000)) &&
		            		(Integer.parseInt(data.get(1)) & 2) <= 0) {
	
		            	numOfDiscReads++; // increase number of discordant reads
		            	qualifiedPair = true;
		            }
	
		            // Discordant pairs with the 1st read in the 3' flank and mate in the insertion
		            if (Integer.parseInt(data.get(3)) >= position && Integer.parseInt(data.get(3)) + data.get(9).length() <= end &&
		            		(Integer.parseInt(data.get(1)) & 16) > 0 &&
		            		PatternSplitter.FULL_MATCH_M.matcher(data.get(5)).find() && //PatternSplitter.FULL_MATCH_M.matcher(dataOther.get(5)).find() &&
		            		(!data.get(2).equals(dataOther.get(2)) || (data.get(2).equals(dataOther.get(2)) && Math.abs(Integer.parseInt(data.get(3)) - Integer.parseInt(dataOther.get(3))) > 1000000)) &&
		            		(Integer.parseInt(data.get(1)) & 2) <= 0) {
	
		            	numOfDiscReads++;// increase number of discordant reads
		            	qualifiedPair = true;
		            }
		            
		            if (!qualifiedPair) { continue; }
	            }

	            // Calculate the coverage of each region
	            if (Integer.parseInt(data.get(3)) < position ) {
	            	totReadLengthLeftRegion += data.get(9).length();
	            } else {
	            	totReadLengthRightRegion += data.get(9).length();
	            }

	            char r1, r2;
	            String n1 = ""; String n2 = ""; String S1 = ""; String S2 = ""; String Q1 = ""; String Q2 = "";
	            Integer N = 0;
	            String bam1 = ""; String bam2 = "";

	            // Orientation of the anchoring read
	            if ((Integer.parseInt(data.get(1)) & 16) > 0) {
	            	r1 = '-';
	            	data.set(9, reverseCompDNA(data.get(9)));
	            } else {
	            	r1 = '+';
	            }
	            // Orientation of the MEI read
	            if ((Integer.parseInt(data.get(1)) & 32) > 0) {
	            	r2 = '-';
	            	dataOther.set(9, reverseCompDNA(dataOther.get(9)));
	            } else {
	            	r2 = '+';
	            }

	            // read1 and 2 in a pair
	            if ((Integer.parseInt(data.get(1)) & 64) > 0) {
	            	// Read #1 is first in pair
	            	n1 = data.get(0) + "_1 " + data.get(2) + ":" + data.get(3) + "|" + r1;
	            	S1 = data.get(9);
	            	Q1 = data.get(10);
	            	bam1 = samLine;

	            	n2 = data.get(0) + "_2 " + dataOther.get(2) + ":" + dataOther.get(3) + "|" + r2;
	            	S2 = dataOther.get(9);
	            	Q2 = dataOther.get(10);
	            	bam2 = samLine2;

	            	N = 1;
	            } else if ((Integer.parseInt(data.get(1)) & 128) > 0) {
	            	// Read #1 is second in pair
	            	n2 = data.get(0) + "_1 " + data.get(2) + ":" + data.get(3) + "|" + r1;
	            	S2 = data.get(9);
	            	Q2 = data.get(10);
	            	bam2 = samLine;

	            	n1 = data.get(0) + "_2 " + dataOther.get(2) + ":" + dataOther.get(3) + "|" + r2;
	            	S1 = dataOther.get(9);
	            	Q1 = dataOther.get(10);
	            	bam1 = samLine2;

	            	N = 2;
	            }

	            String smp = bamfile.toString(); //=~/([^\/]+)\.bam/

	            matcher = Pattern.compile("([^\\/]+)\\.bam").matcher(bamfile);
	            if (matcher.find()) {
	            	smp = matcher.group(1);
	            }

	            // Record the ID, seq, quality, and read1/2 info for each pair with read1 first
	            Map<String, String> values = new HashMap<String, String>();
	            values.put("ID1", n1);
	            values.put("S1", S1);
	            values.put("Q1", Q1);
	            values.put("ID2", n2);
	            values.put("S2", S2);
	            values.put("Q2", Q2);
	            values.put("S", smp);
	            values.put("N", N.toString());
	            values.put("bam1", bam1);
	            values.put("bam2", bam2);

	            sra2FA.put(data.get(0), values);
            } // end of a single BAM file

            if (IOParameters.FILTERS_APPLY && (leftNumOfSplitReads + rightNumOfSplitReads + numOfDiscReads)/3 >= IOParameters.SPLIT_READS_CUTOFF.get(IOParameters.ME_TYPE)) {
            	//continue;
            }

		} // end of bam file loop

        LOGGER.info("NUMBER OF SPLIT-READS: left - " + leftNumOfSplitReads + " right - " + rightNumOfSplitReads + "; \tNUMBER OF DISCORDANT READS: " + numOfDiscReads + "\n");

		LOGGER.info(chromosome + "_" + position + " has " + raw + " reads\n");
		LOGGER.info(sra2FA.keySet().size() + " read pairs are qualified\n");

		if (raw == 0 || sra2FA.isEmpty()) {
			return -1;
		}

		// ---------------------------------------------------------------
		// CREATE OUTPUT
		// ---------------------------------------------------------------

		// Create output directory if it doesn't exist
		IOGeneralHelper.createOutDir("/disc_reads");
		String outfilename, outfilename2;
		BufferedWriter outwriter = null;
		BufferedWriter outwriter2 = null;

		if (IOParameters.OUTPUT_FORMAT.equals(".fa")) {
			outfilename = System.getProperty("user.dir") + "/disc_reads/" + chromosome + "_" + position + IOParameters.OUTPUT_FORMAT;
			outwriter = new BufferedWriter(new FileWriter(outfilename));
		} else {
			outfilename = System.getProperty("user.dir") + "/disc_reads/" + chromosome + "_" + position + "_1" + IOParameters.OUTPUT_FORMAT;
			outfilename2 = System.getProperty("user.dir") + "/disc_reads/" + chromosome + "_" + position + "_2" + IOParameters.OUTPUT_FORMAT;

			outwriter = new BufferedWriter(new FileWriter(outfilename));
			outwriter2 = new BufferedWriter(new FileWriter(outfilename2));
		}

		// Create output directory for raw .bam data
		IOGeneralHelper.createOutDir("/disc_reads/BAM");
		String bamOutfilename =  System.getProperty("user.dir") + "/disc_reads/BAM/" + chromosome + "_" + position + ".txt";
		BufferedWriter bamOutwriter = new BufferedWriter(new FileWriter(bamOutfilename));

		// Write output
		for (String sraKey : sra2FA.keySet()) {
			Map<String, String> t = sra2FA.get(sraKey);

			if (t == null) {
				continue;
			}

			String seq = "";
			String bamSeq = "";
			if (t.get("N").equals("1")) {
				if (IOParameters.OUTPUT_FORMAT.equals(".fa")) {
					seq = ">" + t.get("ID1") + "|" + t.get("S") + "\n" + t.get("S1") + "\n>" + t.get("ID2") + "\n" + t.get("S2") + "\n";
					outwriter.write(seq);
				} else {
					outwriter.write("@" + t.get("ID1") + "\n" + t.get("S1") + "\n" + t.get("Q1") + "\n");
					outwriter2.write("@" + t.get("ID2") + "\n" + t.get("S2") + "\n" + "+" + "\n "+ t.get("Q2") + "\n");
				}
				bamSeq = t.get("bam1") + "\n" + t.get("bam2") + "\n";
			} else {
				if (IOParameters.OUTPUT_FORMAT.equals(".fa")) {
					seq = ">" + t.get("ID2") + "|" + t.get("S") + "\n" + t.get("S2") + "\n>" + t.get("ID1") + "\n" + t.get("S1") + "\n";
					outwriter.write(seq);
				} else {
					outwriter.write("@" + t.get("ID2") + "\n" + t.get("S2") + "\n" + t.get("Q2") + "\n");
					outwriter2.write("@" + t.get("ID1") + "\n" + t.get("S1") + "\n" + "+" + "\n " + t.get("Q1") + "\n");
				}
				bamSeq = t.get("bam2") + "\n" + t.get("bam1") + "\n";
			}

			bamOutwriter.write(bamSeq);
		}
		outwriter.close();
		if (outwriter2 != null) {
			outwriter2.close();
		}
		bamOutwriter.close();

		data.clear();
		dataOther.clear();
		sra2FA.clear();
		return (totReadLengthLeftRegion + totReadLengthRightRegion) / (2 * IOParameters.FLANKING_REGION);
	}
	

	/**
	 * Collect concordant reads in the middle of insertion
	 * @param me
	 * @throws IOException
	 * @throws InputParametersException
	 * @throws InterruptedException
	 */
	public void collectConcordantReads(MEInsertion me) throws IOException, InterruptedException, InputParametersException {
		String typeFileName = System.getProperty("user.dir") + "/src/com/yg/input/ref/ParsedAlu/" + "hg19_" + me.getTypeOfMEI() + ".BED";
		BEDParser bedParser = new BEDParser(typeFileName, true);
		// Get list of locations that contain MEI of specific type
		List<BEDData> refLocations = bedParser.parse();

		// For each location collect concordant reads
		getConcordantReads(me, refLocations);
	}

	/**
	 * Using SAMtools collect concordant reads that can cover the middle part of event
	 * @param chromosome
	 * @param position
	 * @throws IOException
	 * @throws InterruptedException
	 * @throws InputParametersException
	 */
	public void getConcordantReads(MEInsertion me, List<BEDData> locations) throws IOException, InterruptedException, InputParametersException {

		// Create output directory if it doesn't exist
		IOGeneralHelper.createOutDir("/conc_reads");

		// Number of raw reads
		int raw = 0;

		// Start and end position in ref genome taking into account just gap
		long start = 0;
		long end = 0;

		Map<String, Map<String, String>> sra2FA = new HashMap<String, Map<String, String>>();

		for (BEDData loci : locations) {
			if (!loci.getName().contains(me.getTypeOfMEI())) {
				continue;
			}

			start = loci.getChromStart() + me.getContig1AlignLength();
			end = loci.getChromEnd() - me.getContig2AlignLength();

			// ----------------------------------------------------
			// RUN SAMTOOLS
			// ----------------------------------------------------

			// For all specified .bam files
			for (String bamfile : IOParameters.LIST_OF_BAM_FILES) {

				// Run SAM tools from command line

				// Commands for running SAM tools to collect concordant reads
				command = new ArrayList<String>();
			    command.add(IOParameters.SAMTOOLS_PATH  + "samtools");
			    command.add("view");
			    command.add("-f 2");
			    command.add(bamfile.toString());
			    command.add(loci.getChrom() + ":" + start + "-" + end);

			    processBuilder = new ProcessBuilder(command);
	            process = processBuilder.start();

	            LOGGER.info("processing " + bamfile.toString() + " for " + loci.getChrom() + ":" + start + "-" + end + "\n");

				// Collect error messages
	            errStream = new ProcessStream(process.getErrorStream(), "ERROR");
	            errStream.start();

	            // Catch error
	            if (errStream.getOutput() != null && !errStream.getOutput().equals("") && errStream.getOutput().length() != 0) {
	            	throw new InputParametersException("SAMTOOLS ERROR:\n" + errStream.getOutput());
	            } else {
	            	errStream.cleanBuffer();
	            }

	            // Collect output
	            outputStream = new ProcessStream(process.getInputStream(), "OUTPUT");
	            outputStream.start();

	            process.waitFor();

	            // Process SAM tools output
	            if (outputStream.getOutput() == null || outputStream.getOutput().equals("")) {
	            	continue;
	            }

	            samtoolsOutput = PatternSplitter.toList(PatternSplitter.PTRN_NEWLINE_SPLITTER, outputStream.getOutput().trim());
	            outputStream.cleanBuffer();

	            LOGGER.info(bamfile.toString() + " SAM tools found reads: " + samtoolsOutput.size() + "\n");

				// For each output sequence
	            for (String samLine : samtoolsOutput) {
	            	raw++; // count the number of reads

	            	// Get data for each read
	            	data = PatternSplitter.toList(PatternSplitter.PTRN_TAB_SPLITTER, samLine);

	            	if (data.get(6).equals("=")) {
	            		data.set(6, data.get(2));
	            	}

	                // Retrieve the other read from the BAM file

	                // Commands for running SAM tools to get other read
	                command.clear();
	                command.add(IOParameters.SAMTOOLS_PATH  + "samtools");
	                command.add("view");
	                command.add("-f 2"); // unmapped read 1
	                command.add(bamfile.toString());
	                command.add(data.get(6) + ":" + data.get(7) + "-" + data.get(7));

	                processBuilder = new ProcessBuilder(command);
	                process = processBuilder.start();

	                // Collect error messages
	                errStream = new ProcessStream(process.getErrorStream(), "ERROR");
	                errStream.start();

	                // Catch error
	                if (errStream.getOutput() != null && !errStream.getOutput().equals("") && errStream.getOutput().length() != 0) {
	                	throw new InputParametersException("SAMTOOLS 2 ERROR:\n" + errStream.getOutput());
	                } else {
	                	errStream.cleanBuffer();
	                }

	                // Collect output
	                outputStream = new ProcessStream(process.getInputStream(), "OUTPUT");
	                outputStream.start();

		            process.waitFor();

		            // Process to get the line with the same sequence id
		            dataOther = null;
		            List<String> otherReads = PatternSplitter.toList(PatternSplitter.PTRN_NEWLINE_SPLITTER, outputStream.getOutput().trim());
		            for (String otherReadLine : otherReads) {
		            	dataOther = PatternSplitter.toList(PatternSplitter.PTRN_TAB_SPLITTER, otherReadLine);
		            	if (dataOther.get(0).equals(data.get(0))) {
		            		break;
		            	}
		            	dataOther = null;
		            }
		            otherReads.clear();
		            outputStream.cleanBuffer();

		            if (dataOther == null || dataOther.size() == 0) {
		            	continue;
		            }

		            char r1, r2;
		            String n1 = ""; String n2 = ""; String S1 = ""; String S2 = ""; String Q1 = ""; String Q2 = "";
		            Integer N = 0;
		            // Orientation of the anchoring read
		            if ((Integer.parseInt(data.get(1)) & 16) > 0) {
		            	r1 = '-';
		            	data.set(9, reverseCompDNA(data.get(9)));
		            } else {
		            	r1 = '+';
		            }
		            // Orientation of the MEI read
		            if ((Integer.parseInt(data.get(1)) & 32) > 0) {
		            	r2 = '-';
		            	dataOther.set(9, reverseCompDNA(dataOther.get(9)));
		            } else {
		            	r2 = '+';
		            }

		            // read1 and 2 in a pair
		            if ((Integer.parseInt(data.get(1)) & 64) > 0) {
		            	// Read #1 is first in pair
		            	n1 = data.get(0) + "_1 " + data.get(2) + ":" + data.get(3) + "|" + r1;
		            	S1 = data.get(9);
		            	Q1 = data.get(10);

		            	n2 = data.get(0) + "_2 " + dataOther.get(2) + ":" + dataOther.get(3) + "|" + r2;
		            	S2 = dataOther.get(9);
		            	Q2 = dataOther.get(10);

		            	N = 1;
		            } else if ((Integer.parseInt(data.get(1)) & 128) > 0) {
		            	// Read #1 is second in pair
		            	n2 = data.get(0) + "_1 " + data.get(2) + ":" + data.get(3) + "|" + r1;
		            	S2 = data.get(9);
		            	Q2 = data.get(10);

		            	n1 = data.get(0) + "_2 " + dataOther.get(2) + ":" + dataOther.get(3) + "|" + r2;
		            	S1 = dataOther.get(9);
		            	Q1 = dataOther.get(10);

		            	N = 2;
		            }

		            String smp = bamfile.toString();

		            Matcher m = Pattern.compile("([^\\/]+)\\.bam").matcher(bamfile);
		            if (m.find()) {
		            	smp = m.group(1);
		            }

		            // Record the ID, seq, quality, and read1/2 info for each pair with read1 first
		            Map<String, String> values = new HashMap<String, String>();
		            values.put("ID1", n1);
		            values.put("S1", S1);
		            values.put("Q1", Q1);
		            values.put("ID2", n2);
		            values.put("S2", S2);
		            values.put("Q2", Q2);
		            values.put("S", smp);
		            values.put("N", N.toString());

		            sra2FA.put(data.get(0), values);
	            } // end of a single .bam file
			} // end of .bam file
		} // end of locations list

		LOGGER.info(me.getChromosome() + "_" + me.getPosition() + " has " + raw + " concordant reads\n");

		// ---------------------------------------------------------------
		// CREATE OUTPUT
		// ---------------------------------------------------------------

		// Create output directory if it doesn't exist
		IOGeneralHelper.createOutDir("/conc_reads/" + IOParameters.ME_TYPE);
		// Name of the sequence output file
		String outfilename =  System.getProperty("user.dir") + "/conc_reads/" + IOParameters.ME_TYPE + "/" + IOParameters.ME_TYPE + "." + me.getChromosome() + "_" + me.getPosition() + IOParameters.OUTPUT_FORMAT;
		BufferedWriter outwriter = new BufferedWriter(new FileWriter(outfilename));

		for (String sraKey : sra2FA.keySet()) {
			Map<String, String> t = sra2FA.get(sraKey);

			if (t == null) {
				continue;
			}

			String seq = "";
			if (t.get("N").equals("1")) {
				seq = ">" + t.get("ID1") + "|" + t.get("S") + "\n" + t.get("S1") + "\n>" + t.get("ID2") + "\n" + t.get("S2") + "\n";
			} else {
				 // always print the anchoring read first with sample info
				seq = ">" + t.get("ID2") + "|" + t.get("S") + "\n" + t.get("S2") + "\n>" + t.get("ID1") + "\n" + t.get("S1") + "\n";
			}
			outwriter.write(seq);
		}
		outwriter.close();
	}
}
