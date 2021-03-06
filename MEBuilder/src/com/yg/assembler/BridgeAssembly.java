package com.yg.assembler;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import org.apache.commons.io.FileUtils;

import com.yg.exceptions.FileException;
import com.yg.exceptions.InputParametersException;
import com.yg.graph.AdjacencyMatrix;
import com.yg.graph.Edge;
import com.yg.graph.Graph;
import com.yg.graph.Node;
import com.yg.graph.Tree;
import com.yg.graph.Vertex;
import com.yg.io_handlers.IOParameters;
import com.yg.io_handlers.InputDataHandler;
import com.yg.models.Bl2seqOutputData;
import com.yg.models.FASTASeq;
import com.yg.models.MEInsertion;
import com.yg.parsers.Bl2seqOutTabParser;
import com.yg.parsers.FastaParser;
import com.yg.utilities.IOGeneralHelper;

/**
 * This class aligns all contigs and flanking sequences all-against-all;
 * Filters out contigs that are fully aligned to the reference;
 * Searches for paths between the left and right flanking;
 * Validates overlaps between contigs;
 * Merges contigs from a valid path into a scaffold
 * 
 * @author Yaroslava Girilishena
 *
 */

public class BridgeAssembly {
	public final static Logger LOGGER = Logger.getLogger(Logger.GLOBAL_LOGGER_NAME); // init logger
	
	private List<String> contigsFiles;
	
	private List<Vertex> nodes;
    private List<Edge> edges;
    
    public AdjacencyMatrix adjacencyMatrix; // for tree path
    public AdjacencyMatrix adjacencyMatrixSymmetric; //for graph path
    
    private Graph graph;
    
    private MEInsertion me;
    private String chromosome;
    private long position;
    
    /**
     * Constructor
     * @param chromosome
     * @param position
     */
    public BridgeAssembly(String chromosome, long position) {
    	this.chromosome = chromosome;
    	this.position = position;
    }
    
    public BridgeAssembly(MEInsertion me) {
    	this.me = me;
    	this.chromosome = me.getChromosome();
    	this.position = me.getPosition();
    }
    
    /**
     * Build the bridge - merge contigs and extand the sequence from both sides as much as possible
     * @param contigsDir
     * @param chromosome
     * @param position
     * @throws IOException 
     * @throws InterruptedException 
     * @throws InputParametersException 
     * @throws FileException 
     */
    public String findMaxOverlap(String contigsDir) throws IOException, InputParametersException, InterruptedException, FileException {
    	// Create output directory for pair-wise alignments
    	String alignmentOutDir = "/intermediate_output/bl2seq_output/" + IOParameters.ME_TYPE + "/" + IOParameters.ME_TYPE + "." + chromosome + "_" + position + "/paired_contigs";
    	IOGeneralHelper.createOutDir(alignmentOutDir);
    	// Remove all existing files from that directory
    	FileUtils.cleanDirectory(new File(System.getProperty("user.dir") + alignmentOutDir));
    				
    	// Create output directory for flanking alignments
    	String flankingAlignmentOutDir = "/intermediate_output/bl2seq_output_flanking/" + IOParameters.ME_TYPE;
    	IOGeneralHelper.createOutDir(flankingAlignmentOutDir);
    	
    	String finalMergedContigs = null; // file with final merged contigs
    	
    	LOGGER.info("BRIDGE ASSEMBLY for " + this.chromosome + "_" + this.position + "\n");
    	
    	// Check if directory exists
		if (contigsDir == null || contigsDir.length() == 0) {
			return null;
		}
		// Get contigs files in .fa format
    	contigsFiles = IOGeneralHelper.getListOfFAFiles(contigsDir);
    	if (contigsFiles.size() == 0) {
			return null;
		}
    	    	
    	try {
    		// Get flanking requences
        	List<String> flankingSequences = FastaParser.collectFlankingSequences(contigsDir, chromosome, position);
        	
        	LOGGER.info("Do all-against-all pairwise alignment for contigs\n");
        	
			// Filter out contigs fully aligned to flanking region
    		filterOutFullyAlignedToFlanking(flankingAlignmentOutDir);
    		
    		// Align all contigs and flanking sequences
    		alignAllPairsOfContigs(alignmentOutDir, flankingSequences);
    		List<String> alignedContigs;
    		
    		// Print matrix - TEST
    		LOGGER.info("\n Adjacency matrix for contigs: " + contigsFiles.size() + "\n" + adjacencyMatrix.toString());
    		
    		// Build the graph and find the shortest path between left flanking [0] and right flanking [end of list] sequences 
    		LOGGER.info("\n");
    		List<List<Vertex>> paths = findAllPaths(0, contigsFiles.size()-1);
    		
    		boolean insPathFound = false;
    		int index = 0;
    		
    		if (paths != null && !paths.isEmpty()) {
    			LOGGER.info("MULTIPLE PATHS found!\n");
    			// For each path in found paths, merge contigs and align them to consensus
    			for (List<Vertex> path: paths) {
    				LOGGER.info("PATH to align: #" + index);
    				// Get list of contigs files
        			alignedContigs = getListOfContigsFromPath(path, null);
        			
        			// Merge contigs from a path
        			finalMergedContigs = mergeContigsFromPath(alignedContigs, alignmentOutDir);
        			
        			if (finalMergedContigs.contains("left") && finalMergedContigs.contains("right")) {
        				// Align to consensus 
            			if (alignInsertionToConsensus(finalMergedContigs, true) && me.getSequence().length() >= IOParameters.MIN_MEI_LENGTH.get(IOParameters.ME_TYPE)) {
            				LOGGER.info("PATH #" + index + " aligned SUCCESSFULLY\n");
            				insPathFound = true;
            				break;
            			} else {
            				LOGGER.info("PATH #" + index + "  NOT aligned\n");
            			}
        			} else {
        				LOGGER.info("PATH #" + index + "  NOT merged\n");
        			}
        			
        			index++; // just for logging purposes 
    			}
    		}
    		
    		// If two breakpoint sequences are disconnected, build the longest contigs from both sides
    		if (!insPathFound || paths == null || paths.isEmpty()) {
    			LOGGER.info("NO CONTINUOUS path found...\n");
    			String leftMergedContigsFile = null, rightMergedContigsFile = null;
    			
    			AdjacencyMatrix tempMatrix = new AdjacencyMatrix(adjacencyMatrix);
    			adjacencyMatrix.clearCol(adjacencyMatrix.getCols() - 1); // clear last column to avoid path to the right flanking
    			
    			System.out.println("\nAdjacency matrix for contigs: " + contigsFiles.size() + "\n" + adjacencyMatrix.toString());
    			
    			// Create a tree and find the longest existing path from the left flank
    			ArrayList<Node<String>> longestLeftPath = findTheLongestPath(0);
    			// Merge contigs from the left path
    			if (longestLeftPath != null && longestLeftPath.size() != 0) {
    				LOGGER.info("LONGEST LEFT path found!\n");
    				alignedContigs = getListOfContigsFromPath(null, longestLeftPath);
    				leftMergedContigsFile = mergeContigsFromPath(alignedContigs, alignmentOutDir);
    			} else {
    				leftMergedContigsFile = flankingSequences.get(0);
    			}
    			
    			// Adjust matrix for the right side assembly 
    			adjacencyMatrix.copyFrom(tempMatrix);
    			adjacencyMatrix.makeSymmetricMatrix();
    			adjacencyMatrix.swipeRows(0, adjacencyMatrix.getRows()-1);
    			adjacencyMatrix.swipeCols(0, adjacencyMatrix.getCols()-1);
    			adjacencyMatrix.clearCol(adjacencyMatrix.getCols()-1);
    			
        		System.out.println("\nREVERSED Adjacency matrix for contigs: " + contigsFiles.size() + "\n" + adjacencyMatrix.toString());

    			// Adjust contigs list
    			String temp = contigsFiles.get(0);
    			contigsFiles.set(0, contigsFiles.get(contigsFiles.size() - 1));
    			contigsFiles.set(contigsFiles.size()-1, temp);
    			
    			// Create a tree and find the longest existing path from the right flank
    			ArrayList<Node<String>> longestRightPath = findTheLongestPath(0);
    			
    			// Merge contigs from the right path
    			if (longestRightPath != null && longestRightPath.size() != 0) {
    				LOGGER.info("LONGEST RIGHT path found!\n");
    				alignedContigs = getListOfContigsFromPath(null, longestRightPath);
    				rightMergedContigsFile = mergeContigsFromPath(alignedContigs, alignmentOutDir);
    			} else {
    				rightMergedContigsFile = flankingSequences.get(1);
    			}
    			
    			// Merge files with extended contigs
    			List<File> filesToMerge = new ArrayList<File>();
    			if (leftMergedContigsFile != null) filesToMerge.add(new File(leftMergedContigsFile));
    			if (rightMergedContigsFile != null) filesToMerge.add(new File(rightMergedContigsFile));
    			    			
    			finalMergedContigs = System.getProperty("user.dir")  + alignmentOutDir + "/" + "left_right.fa";
    			IOGeneralHelper.mergeFiles(filesToMerge, finalMergedContigs);
    			
    			// Align paths to the consensus
    			alignInsertionToConsensus(finalMergedContigs, false);
    			
    			if (me.isFull() || me.isPartialChar()) {
    				String seq = me.getFlankingL() + me.getSequence() + me.getFlankingR();
    				String desc = "left_right";
    				FASTASeq mergedSequence = new FASTASeq(desc, seq);
    				
        			IOGeneralHelper.writeFASeqIntoFile(finalMergedContigs, mergedSequence, false);
    			}
    		}
			
		} catch (IOException e) {
			e.printStackTrace();
		}
    	
    	// Create directory for merged contigs
    	String finalMergedFilesDir = "/intermediate_output/merged_contigs/" + IOParameters.ME_TYPE + "/" + IOParameters.ME_TYPE + "." + chromosome + "_" + position;
    	IOGeneralHelper.createOutDir(finalMergedFilesDir);
    	// Remove all existing files from that directory
    	FileUtils.cleanDirectory(new File(System.getProperty("user.dir") + finalMergedFilesDir));
    	
    	// Copy final merge into another file
    	if (finalMergedContigs != null) {
    		Files.copy(new File(finalMergedContigs).toPath(), 
    				new File(System.getProperty("user.dir") + finalMergedFilesDir + "/merged_contigs.fa").toPath(), 
    				StandardCopyOption.REPLACE_EXISTING);
    		// Assign new file
    		finalMergedContigs = System.getProperty("user.dir") + finalMergedFilesDir + "/merged_contigs.fa";
    	}
    	
    	
    	return finalMergedContigs;
    }
    
	 // -----------------------------------------------------------------------------------
	 // ALIGN INSERTION TO CONSENSUS
     // -----------------------------------------------------------------------------------
    
    /**
     * Align the insertion to the consensus sequence
     * @param finalMergedContigs
     * @return
     * @throws IOException
     * @throws InterruptedException
     * @throws InputParametersException
     */
    public boolean alignInsertionToConsensus(String finalMergedContigs, boolean fullPath) throws IOException, InterruptedException, InputParametersException {
    	// Check obtained contigs after assembly
    	Map<String, FASTASeq> contigs = FastaParser.extractContigs(finalMergedContigs);
		if (contigs == null || contigs.isEmpty()) {
			LOGGER.info("NO MERGED contigs\n");
			return false;
		}
    				
    	// Align contigs to consensus sequence using blastn tool			
		String blastnOutput = BLASTAlignment.alignContigsToConsensus(finalMergedContigs, chromosome, position);
		if (blastnOutput == null || blastnOutput.equals("")) {
			LOGGER.info("Path is not aligned to the consensus\n");
			return false;
		}
		
		// Get information about MEI from alignment 
		if (contigs.keySet().size() == 1 && fullPath) {
			BLASTAlignment.collectMEinfoForFullSeq(this.me, contigs, blastnOutput);
		} else if (contigs.keySet().size() > 1 || !fullPath) {
			BLASTAlignment.collectMEinfoForTwoPaths(this.me, contigs, blastnOutput);
		}
		
		return me.isFull();
    }
    
    // -----------------------------------------------------------------------------------
 	// MERGE CONTIGS FROM A PATH
    // -----------------------------------------------------------------------------------
    
    /**
     * Get list of contigs from the graph path or tree path
     * @param vertexPath
     * @param nodePath
     * @return
     */
    public List<String> getListOfContigsFromPath(List<Vertex> vertexPath, ArrayList<Node<String>> nodePath) {
    	List<String> listOfContigs = new ArrayList<String>();
    	
    	if (vertexPath != null) {
	    	for (Vertex vertex: vertexPath) {
	    		listOfContigs.add(vertex.getId());
	    	}
    	} 
    	
    	if (nodePath != null) {
	    	for (Node<String> node: nodePath) {
	    		listOfContigs.add(node.getData());
	    	}
    	}
    	
    	return listOfContigs;
    }
    
    /**
     * Merge contigs from a path
     * 
     * @param contigsFiles
     * @param bl2seqOutputDir
     * @return
     * @throws IOException
     * @throws InputParametersException
     * @throws InterruptedException
     */
    public String mergeContigsFromPath(List<String> contigsFiles, String alignmentOutDir) throws IOException, InputParametersException, InterruptedException {
    	Bl2seqOutTabParser bl2seqRes;
    	String alignmentFile;
    	
    	// Start merging from the first file
    	String mergedFile = contigsFiles.get(0);
    	FASTASeq mergedSequence;
    	
    	for (int i=1; i<contigsFiles.size(); i++) {
			
    		// Align merged contig with next contig in a path
    		alignmentFile = Bl2seqAlignment.runBL2SEQ(mergedFile, contigsFiles.get(i), alignmentOutDir);
    		//bl2seqRes = parseAndValidateAlignment(alignmentFile, query, subject);
    		
    		// Parse bl2seq output file with results
        	bl2seqRes = new Bl2seqOutTabParser(alignmentFile, true);
        	bl2seqRes.parse();
        	
    		// If they are aligned (and they should!)
    		if (bl2seqRes != null && bl2seqRes.alignmentsList.size() !=0) {
    			boolean merged = false;
    			
    			for (Bl2seqOutputData bl2seqOut : bl2seqRes.alignmentsList) {
    				// Merge contigs
    				mergedSequence = mergeContigs(mergedFile, contigsFiles.get(i), bl2seqOut);
    				
            		// Write successfully merged contig into a file
    				if (mergedSequence != null) {
    					
    					// Create new file name
                		String newMergedFile = mergedFile.substring(0, mergedFile.lastIndexOf(".")) + "_" + contigsFiles.get(i).substring(contigsFiles.get(i).lastIndexOf("/") + 1);
                		
            			// Rewrite merged file
            			IOGeneralHelper.writeFASeqIntoFile(newMergedFile, mergedSequence, false);
            			mergedFile = newMergedFile;
            			merged = true;
            			break;
            			
            		}
    			}
    			if (!merged) {
    				LOGGER.info("Two contigs are not merged: " + mergedFile + " and " + contigsFiles.get(i) + "\n");
    			}

    		} else {
    			LOGGER.info("\nTwo contigs for merging are not aligned: " + mergedFile + " and " + contigsFiles.get(i));
    		}
    		
    	}
    	
    	return mergedFile;
    }
    
	/**
	 * Merge two overlapping contigs
	 * @param contig1File
	 * @param contig2File
	 * @return
	 * @throws IOException
	 */
	public FASTASeq mergeContigs(String contig1File, String contig2File, Bl2seqOutputData bl2seqRes) throws IOException {		
		// Parse first overlapping contig (query)
		FastaParser parseContig = new FastaParser(contig1File);
		FASTASeq query = parseContig.parse().get(0);
		
		// Parse second overlapping contig (subject)
		parseContig = new FastaParser(contig2File);
		FASTASeq subject = parseContig.parse().get(0);
		
		String fullSeq = "";
		String reverseContig2 = "";

		if (contig1File.contains(IOParameters.LEFT_FLANK_TAG) && !contig2File.contains(IOParameters.RIGHT_FLANK_TAG)) { // left flanking region
			if (bl2seqRes.subjectStrand == '+') { // subject '+'					
				
				if ((bl2seqRes.subjectStart < 50 || bl2seqRes.queryStart < 50) && 
						Math.abs(bl2seqRes.subjectEnd - subject.getSequence().length()) > 10 &&
						Math.abs(query.getSequence().length() - bl2seqRes.queryEnd) < 15) {
					
					fullSeq = query.getSequence().substring(0, bl2seqRes.queryStart) + 
							subject.getSequence().substring(bl2seqRes.subjectStart);
				}
			} else { // subject '-'
				
				if ((Math.abs(bl2seqRes.subjectStart - subject.getSequence().length()) < 50 || bl2seqRes.queryStart < 50) && 
						bl2seqRes.subjectEnd > 10 && 
						Math.abs(query.getSequence().length() - bl2seqRes.queryEnd) < 15) {
					
					reverseContig2 = InputDataHandler.reverseCompDNA( subject.getSequence().substring(0, bl2seqRes.subjectStart) );

					fullSeq = query.getSequence().substring(0, bl2seqRes.queryStart) + reverseContig2;
				}
			}
		} else if (contig1File.contains(IOParameters.RIGHT_FLANK_TAG) && !contig2File.contains(IOParameters.LEFT_FLANK_TAG)) { // right flanking region
			if (bl2seqRes.subjectStrand == '+') { // subject '+'
				
				if ((Math.abs(bl2seqRes.subjectEnd - subject.getSequence().length()) < 50 || Math.abs(bl2seqRes.queryEnd - query.getSequence().length()) < 50 )&& 
						bl2seqRes.subjectStart > 10 &&
						bl2seqRes.queryStart < 25) {
					
					fullSeq = subject.getSequence().substring(0, bl2seqRes.subjectEnd) +
							query.getSequence().substring(bl2seqRes.queryEnd);
				}
			} else { // subject '-'
				
				if (Math.abs(bl2seqRes.subjectStart - subject.getSequence().length()) > 10 &&
						(bl2seqRes.subjectEnd < 50 || Math.abs(bl2seqRes.queryEnd - query.getSequence().length()) < 50) &&
						bl2seqRes.queryStart < 25) {
					
					reverseContig2 = InputDataHandler.reverseCompDNA( subject.getSequence().substring(bl2seqRes.subjectEnd) );
					
					fullSeq = reverseContig2 + query.getSequence().substring(bl2seqRes.queryEnd);
				}
			}
		} else {
			// Both left and right flanking included
			boolean successful = false;
			
			if (bl2seqRes.subjectStrand == '+') { // subject '+'
				if (bl2seqRes.subjectStart < 25 && Math.abs(bl2seqRes.queryEnd - query.getSequence().length()) < 50) {
					
					fullSeq = query.getSequence().substring(0, bl2seqRes.queryEnd) // merged left path
							+ subject.getSequence().substring(bl2seqRes.subjectEnd); // right flanking
					
					successful = true;
				}
			} 
			
			if (!successful) {
				if (query.getSequence().length() > IOParameters.AVG_INS_LENGTH.get(IOParameters.ME_TYPE) + IOParameters.FLANKING_REGION) {
					fullSeq = query.getSequence()	 // merged left path
							+ subject.getSequence(); // right flanking
				}
			}
		}
				
		// If merged contig is too short 
		if (contig1File.contains(IOParameters.LEFT_FLANK_TAG) && contig1File.contains(IOParameters.RIGHT_FLANK_TAG)) {
			if (fullSeq.length() < IOParameters.AVG_INS_LENGTH.get(IOParameters.ME_TYPE) + 2*IOParameters.FLANKING_REGION) {
				return null;
			}
		} else if (fullSeq.length() < 200) {
			return null;
		}
		// Create FA sequence of the merged contig
		FASTASeq mergedContig = new FASTASeq(query.getDescription() + "_" + subject.getDescription(), fullSeq);
		return mergedContig;
	}
	
	// -----------------------------------------------------------------------------------
	// FILTER OUT CONTIGS FULLY ALIGNED TO FLANKING
    // -----------------------------------------------------------------------------------
	
	/**
	 * Align contigs to the reference genome 
	 * and filter out the ones that are fully mapped either to one of the flanks or to both
	 * @param alignmentOutDir
	 */
	public void filterOutFullyAlignedToFlanking(String alignmentOutDir) {
    	// Get file with flanking sequence
    	String refSeq = System.getProperty("user.dir") + "/intermediate_output/ref_flanking/" + IOParameters.ME_TYPE + "/" + chromosome + "_" + position + ".fa";
    	
    	String outFile; // alignment output file
    	Bl2seqOutTabParser bl2seqRes;
    	
    	List<String> fullAllignments = new ArrayList<String>();
    	
    	// Align each contig to flanking sequence and validate the alignment
    	for (int i=0; i<contigsFiles.size(); i++) {
			try {				
				// Align contig to ref using bl2seq  tool 
				// and get the output file with alignment results
				outFile = Bl2seqAlignment.runBL2SEQ(refSeq, contigsFiles.get(i), alignmentOutDir);
			
				// Parse second overlapping contig (subject)
				FastaParser parsedFA = new FastaParser(contigsFiles.get(i));
				FASTASeq subject = parsedFA.parse().get(0);
				
				// Parse bl2seq output file with results
	        	bl2seqRes = new Bl2seqOutTabParser(outFile, false);
	        	bl2seqRes.parse();
	        	
				// If no hits found
				if (bl2seqRes == null || bl2seqRes.alignmentsList == null ||  bl2seqRes.alignmentsList.size() == 0) {
					bl2seqRes = null;
		    		continue;
		    	} else {
		    		// Check every alignment to the reference
		    		for (Bl2seqOutputData bl2seqOut : bl2seqRes.alignmentsList) {
		    			
		    			int sStart = bl2seqOut.subjectStart < bl2seqOut.subjectEnd ? bl2seqOut.subjectStart : bl2seqOut.subjectEnd;
			    		int sEnd = bl2seqOut.subjectStart > bl2seqOut.subjectEnd ? bl2seqOut.subjectStart : bl2seqOut.subjectEnd;
			    		
			    		int subjectLeftover = sStart - 1 > subject.getSequence().length() - sEnd ? sStart - 1 : subject.getSequence().length() - sEnd;

			    		if (subjectLeftover < 10) {
			    			LOGGER.info(contigsFiles.get(i) + " fully aligned to both flanking - REMOVED");
			    			fullAllignments.add(contigsFiles.get(i));
			    			bl2seqRes = null;
				    		break;
			    		}
		    		}
		    	}
				
			} catch (IOException | InputParametersException | InterruptedException e) {
				e.printStackTrace();
			}
    	}
    	contigsFiles.removeAll(fullAllignments);
	}
	
    // -----------------------------------------------------------------------------------
	// ALIGN ALL PAIRS OF CONTIGS
    // -----------------------------------------------------------------------------------
    
    /**
     * Do all-against-all pairwise alignment of contigs and flanking sequences
     * @param contigsDir
     * @param alignmentOutDir
     * @param chromosome
     * @param position
     * @return
     * @throws IOException
     * @throws FileException 
     */
    public void alignAllPairsOfContigs(String alignmentOutDir, List<String> flankingSequences) throws IOException, FileException {  	

    	// Add flanking sequences to the contigs list
    	contigsFiles.add(0, flankingSequences.get(0)); //add left flanking at the beginning
    	contigsFiles.add(flankingSequences.get(1)); // add right flanking at the end

    	// Define the adjacency matrix
    	adjacencyMatrix = new AdjacencyMatrix(contigsFiles.size());

    	String outFile;
    	// For each contigs pair, run the alignment tool
    	for (int i=0; i<contigsFiles.size()-1; i++) {
    		for (int j=i+1; j<contigsFiles.size(); j++) {
    			try {
    				// Left and right flanking should not be counted as aligned
    				if (contigsFiles.get(i).contains(IOParameters.LEFT_FLANK_TAG) && contigsFiles.get(j).contains(IOParameters.RIGHT_FLANK_TAG)
    						|| contigsFiles.get(j).contains(IOParameters.LEFT_FLANK_TAG) && contigsFiles.get(i).contains(IOParameters.RIGHT_FLANK_TAG)) {
    					continue;
    				}
    				// Align pairs of contigs using bl2seq tool and get the output file with alignment results
					outFile = Bl2seqAlignment.runBL2SEQ(contigsFiles.get(i), contigsFiles.get(j), alignmentOutDir);
					
					// Parse the first overlapping contig (query)
					FastaParser parseContig = new FastaParser(contigsFiles.get(i));
					FASTASeq query = parseContig.parse().get(0);
					
					// Parse the second overlapping contig (subject)
					parseContig = new FastaParser(contigsFiles.get(j));
					FASTASeq subject = parseContig.parse().get(0);
					
					// Filter out pairs with either bad or no alignment
					Bl2seqOutputData bl2seqRes = parseAndValidateAlignments(outFile, query, subject);
					
					if (bl2seqRes == null) {
						// Remove file with no alignment
			    		//IOGeneralHelper.deleteFile(outFile, null); //"has bad or no alignment - REMOVED");
						//LOGGER.info(outFile + " has bad or no alignment");
			    		continue;
					}
					
					// Save aligned pair into adjacency matrix 
					adjacencyMatrix.items[i][j] = bl2seqRes.subjectLeftover;
					
				} catch (IOException | InputParametersException | InterruptedException e) {
					e.printStackTrace();
				}
    		}
    	}
    }
    
    /**
     * Parse and validate the alignment between contigs and flanking sequences
     * @param alignmentFile
     * @return
     * @throws IOException
     */
    public Bl2seqOutputData parseAndValidateAlignments(String alignmentFile, FASTASeq query, FASTASeq subject) throws IOException {
    	try {
    		// Parse bl2seq output file with results
        	Bl2seqOutTabParser bl2seqRes = new Bl2seqOutTabParser(alignmentFile, true);
        	bl2seqRes.parse();
        	
			// If no hits found
			if (bl2seqRes == null || bl2seqRes.alignmentsList == null || bl2seqRes.alignmentsList.size() == 0) {
	    		return null;
	    	} else {
    			// Validate the alignments 
	    		Bl2seqOutputData bestAlignment = null;
	    		
	    		for (Bl2seqOutputData bl2seqOut : bl2seqRes.alignmentsList) {
	    			// If the left flank (query) is aligned to a contig (subject), e.g. left_flank -> Contig1
		    		if (query.getDescription().contains(IOParameters.LEFT_FLANK_TAG)) {
		    			
			    		if (bl2seqOut.subjectStrand == '+') { // subject '+'
							if ( (bl2seqOut.subjectStart > 50 && bl2seqOut.queryStart > 50) || 
									Math.abs(bl2seqOut.subjectEnd - subject.getSequence().length()) < 10 ||
									Math.abs(query.getSequence().length() - bl2seqOut.queryEnd) > 15) {
								continue;
							} else {

					    		// Get the subject's leftover length
								bl2seqOut.subjectLeftover = subject.getSequence().length() - bl2seqOut.subjectEnd;
								bestAlignment = bl2seqOut;
								break;
								
							}
						} else { // subject '-'
							
							if ( (Math.abs(bl2seqOut.subjectStart - subject.getSequence().length()) > 50 && bl2seqOut.queryStart > 50) || 
									bl2seqOut.subjectEnd < 10 || 
									Math.abs(query.getSequence().length() - bl2seqOut.queryEnd) > 15) {
								continue;
							} else {

					    		// Get the subject's leftover length
								bl2seqOut.subjectLeftover = bl2seqOut.subjectEnd;
								bestAlignment = bl2seqOut;
								break;
								
							}
						}
		    		}
		    		
		    		// If the right flank (query) is aligned to a contig (subject), e.g. right_flank -> Contig1
		    		else if (query.getDescription().contains(IOParameters.RIGHT_FLANK_TAG)) {
		    			
		    			if (bl2seqOut.subjectStrand == '+') { // subject '+'
		    				
		    				if ( (Math.abs(bl2seqOut.subjectEnd - subject.getSequence().length()) > 50 && Math.abs(bl2seqOut.queryEnd - query.getSequence().length()) > 50)|| 
		    						bl2seqOut.subjectStart < 10 ||
		    						bl2seqOut.queryStart > 25) {
		    					continue;
		    				} else {

					    		// Get the subject's leftover length
		    					bl2seqOut.subjectLeftover = bl2seqOut.subjectStart - 1;
		    					bestAlignment = bl2seqOut;
		    					break;
		    					
							}
		    			} else { // subject '-'
		    				
		    				if (Math.abs(bl2seqOut.subjectStart - subject.getSequence().length()) < 10 ||
		    						(bl2seqOut.subjectEnd > 50 && Math.abs(bl2seqOut.queryEnd - query.getSequence().length()) > 50) ||
		    						bl2seqOut.queryStart > 25) {
		    					continue;
		    				} else {

					    		// Get the subject's leftover length
		    					bl2seqOut.subjectLeftover = subject.getSequence().length() - bl2seqOut.subjectStart;
		    					bestAlignment = bl2seqOut;
		    					break;
		    					
							}
		    			}
		    		}
		    		
		    		// If a contig (query) is aligned to the right flank (subject), e.g. Contig1 -> right_flank
		    		else if (subject.getDescription().contains(IOParameters.RIGHT_FLANK_TAG)) {
		    			
		    			if (bl2seqOut.subjectStrand == '+') { // subject '+'
		    				
		    				if ( (Math.abs(bl2seqOut.queryEnd - query.getSequence().length()) > 50 && Math.abs(bl2seqOut.subjectEnd - subject.getSequence().length()) > 50) ||
		    						bl2seqOut.queryStart < 10 ||
		    						bl2seqOut.subjectStart > 25) {
		    					continue;
		    				} else {

					    		// Get the subject's leftover length
		    					bl2seqOut.subjectLeftover = bl2seqOut.queryStart - 1;
		    					bestAlignment = bl2seqOut;
		    					break;
		    					
							}
		    			} else { // subject '-'
		    				
		    				if (Math.abs(bl2seqOut.queryEnd - query.getSequence().length()) < 10 ||
		    						(bl2seqOut.queryStart > 50 && Math.abs(bl2seqOut.subjectStart - subject.getSequence().length()) > 50)||
		    						bl2seqOut.subjectEnd > 25) {
		    					continue;
		    				} else {

					    		// Get the subject's leftover length
		    					bl2seqOut.subjectLeftover = query.getSequence().length() - bl2seqOut.queryEnd;
		    					bestAlignment = bl2seqOut;
		    					break;
							}
		    			}
		    		} else {
			    		
			    		// For any two contigs, e.g. Contig1_Contig2
			    		int queryStart = bl2seqOut.queryStart < bl2seqOut.queryEnd ? bl2seqOut.queryStart : bl2seqOut.queryEnd;
			    		int queryEnd = bl2seqOut.queryEnd > bl2seqOut.queryStart  ? bl2seqOut.queryEnd : bl2seqOut.queryStart;
			    		int subjectStart = bl2seqOut.subjectStart < bl2seqOut.subjectEnd ? bl2seqOut.subjectStart : bl2seqOut.subjectEnd;
			    		int subjectEnd = bl2seqOut.subjectEnd > bl2seqOut.subjectStart ? bl2seqOut.subjectEnd : bl2seqOut.subjectStart;
			    			
			    		if ( ((queryStart > 25 && Math.abs(queryEnd - query.getSequence().length()) > 25)
			    			|| (subjectStart > 25 && Math.abs(subjectEnd - subject.getSequence().length()) > 25))) {

			    			continue;
			    		} else {
			    			
			    			// Get the subject's leftover length
			    			if (subjectStart > 25 && Math.abs(subjectEnd - subject.getSequence().length()) < 25) {
			    				bl2seqOut.subjectLeftover = subjectStart - 1;
			    			} else if (subjectStart < 25 && Math.abs(subjectEnd - subject.getSequence().length()) > 25) {
			    				bl2seqOut.subjectLeftover = subject.getSequence().length() - subjectEnd;
			    			}
			    			bestAlignment = bl2seqOut;
			    			break;
				    					    		
						}
			    		
		    		}
	    		}
	    		
	    		
	    		// Otherwise, the alignment is valid
	    		return bestAlignment;
	    		
	    	}
		} catch (IOException e) {
			throw e;
		}
    }
    
    // -----------------------------------------------------------------------------------
 	// BUILD GRAPH AND FIND THE SHORTEST PATH BETWEEN LEFT AND RIGHT FLANKING SEQUENCES
 	// -----------------------------------------------------------------------------------
    
    /**
     * Build a graph from overlapping contigs and find all paths between the left and the right flanking
     * @param from
     * @param to
     * @return
     */
    public List<List<Vertex>> findAllPaths(int from, int to) {
    	nodes = new ArrayList<Vertex>();
    	// Build a list of vertices
        for (int i=0; i<contigsFiles.size(); i++) {
        	// vertex id - full path to the contig file; vertex data - just the name of the contig
        	Vertex location = new Vertex(contigsFiles.get(i), contigsFiles.get(i).substring(contigsFiles.get(i).lastIndexOf("/") + 1, contigsFiles.get(i).lastIndexOf(".")));
        	nodes.add(location);
        }
        
        // Create new graph
        graph = new Graph(nodes);
        
        // Build a list of edges
        for (int i=0; i<adjacencyMatrix.getRows(); i++) {
        	for (int j=0; j<adjacencyMatrix.getCols(); j++) {
        		if (adjacencyMatrix.items[i][j] > 0) {
        			graph.addNewEdge(i, j);
        		}
        	}
        }
        // Get all paths in a graph
        List<List<Vertex>> paths = graph.getAllPaths(from, to);
        
        LOGGER.info("Searching for MULTIPLE PATHS for " + chromosome + "_" + position + ": ");
        if (paths != null && !paths.isEmpty()){
        	for (int i=0; i<graph.paths.size(); i++) {
            	LOGGER.info("PATH #" + i + ": ");

        		for (int j=0; j<graph.paths.get(i).size(); j++) {
        			System.out.print(graph.paths.get(i).get(j) + " ");
        		}
        		LOGGER.info("****************************************************\n");
        	}
        } else {
        	LOGGER.info("No path found..." + chromosome + "_" + position);
        	return null;
        }
        
        return paths;
    }
    
    /**
     * Print a list of vertices
     */
    @SuppressWarnings("unused")
	private void printVertices() {
    	 System.out.println("\n NODES:");
         for (Vertex v: nodes) {
         	System.out.println(v.toString());
         }
    }
    
    /**
     * Print a list of edges
     */
    @SuppressWarnings("unused")
	private void printEdges() {
    	System.out.println("\n EDGES:");
        for (Edge e: edges) {
        	System.out.println(e.toString());
        }
    }
    
    // -----------------------------------------------------------------------------------
 	// BUILD TREE AND FIND THE LONGEST PATH
 	// -----------------------------------------------------------------------------------
    
    /**
     * Find the longest path from the root to any leaf
     * @param rootIdx
     * @return
     */
    public ArrayList<Node<String>> findTheLongestPath(int rootIdx) {
    	// Create the root of the tree
    	Node<String> root = new Node<String>(contigsFiles.get(rootIdx), 0);
		
    	// Create a tree, providing the root node
    	Tree<String> tree = new Tree<String>(root);
    	
    	// Add children
    	Node<String> originalRoot = root;
    	createTree(root, 0);
    	
    	tree.setRoot(originalRoot);
    	
		// Get the longest path
		ArrayList<Node<String>> longestPath = tree.getLongestPathFromRootToAnyLeaf();
		
		tree = null;
		root = null;
		
		LOGGER.info("LONGEST PATH for " + chromosome + "_" + position + ": ");
		if (longestPath != null) {
			for (Node<String> node: longestPath) {
				LOGGER.info(node.toString() + " ");
			}
		} else {
			LOGGER.info("No path found...");
		}
		
		return longestPath;
    }
    
    
    /**
     * Build a tree from the adjacency matrix
     * @param root
     * @param row
     */
    private void createTree(Node<String> root, int row) {
    	if (row >= adjacencyMatrix.items[0].length - 1) {
    		return;
    	}
    	
    	// Add new children 
    	for (int j=0; j<adjacencyMatrix.items.length; j++) {
    			
			if (adjacencyMatrix.items[row][j] > 0) {
				root.addChild(new Node<String>(contigsFiles.get(j), adjacencyMatrix.items[row][j]));
			}
			
    	}
    	// For every child, run the create tree function recursively 
    	if (!root.getChildren().isEmpty()) {
			for (Node<String> child: root.getChildren()) {
				if (child != null) {
					createTree(child, contigsFiles.indexOf(child.getData()));
				}
			}
		} else {
			return;
		}
    }
    
    
    /**
     * Print a tree
     * @param root
     * @param row
     */
    @SuppressWarnings("unused")
	private void printTree(Node<String> root, int row) {
    	if (row >= contigsFiles.size() - 1) {
    		return;
    	}
    	    	
    	System.out.print(root + " on row " + row);
    	if (root.getParent() != null) {
        	System.out.print(" parent: " + root.getParent());
    	}
    	System.out.println();
    	
		if (!root.getChildren().isEmpty()) {
			for (Node<String> child: root.getChildren()) {
				printTree(child, contigsFiles.indexOf(child.getData()));
			}
		}
    }
}
