package com.yg.assembler;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import com.yg.exceptions.InputParametersException;
import com.yg.io_handlers.IOParameters;
import com.yg.models.FASTASeq;
import com.yg.parsers.FastaParser;
import com.yg.utilities.ProcessStream;

/**
 * This class removes redundant reads using cd-hit-est before the assembly; 
 * does assembly using CAP3 tool; 
 * parses obtained contigs into separate files.
 * 
 * @author Yaroslava Girilishena
 *
 */
public class CAP3Assembler {
	public final static Logger LOGGER = Logger.getLogger(Logger.GLOBAL_LOGGER_NAME); // init logger
	
	/**
	 * Do assembly using cap3 tool to create contigs
	 * @param chromosome
	 * @param position
	 * @return a file with assembled contigs
	 * @throws IOException
	 * @throws InputParametersException
	 * @throws InterruptedException
	 */
	public static String doAssembly(String chromosome, long position) throws IOException, InputParametersException, InterruptedException {
		String inputFile = removeRedundantReads(chromosome, position); // remove redundant reads
		
		// Setup output directory
		String outputDirectory = System.getProperty("user.dir") + "/intermediate_output/cap3_assembly/" + IOParameters.ME_TYPE + "/" + IOParameters.ME_TYPE + "." + chromosome + "_" + position;

		// Check if input file exists
		if (inputFile == null) {
			return null;
		}
		
		File input = new File(inputFile);
		if (!input.exists() || input.isDirectory()) {
			return null;
		}
		
		// Create output directory if it doesn't exist
		Path path = Paths.get(outputDirectory);
        if (!Files.exists(path)) {
           Files.createDirectories(path);
        }
        
        LOGGER.info("Running CAP3 assembly for: " + inputFile + "\n");
        
        // Commands for running CAP3
 		List<String> cap3Commands = new ArrayList<String>();
 		cap3Commands.add(IOParameters.CAP3_TOOL_PATH + "/cap3");
 		cap3Commands.add(inputFile);
 		cap3Commands.add("-o"); // minimum length of an overlap (in base pairs)
 		cap3Commands.add(IOParameters.OVERLAP_CAP3.toString()); // 16
 		cap3Commands.add("-p"); // overlap percentage identity cutoff
 		cap3Commands.add(IOParameters.PERC_IDENTITY_CAP3.toString()); // 90
 		cap3Commands.add("-t"); // an upper limit of word matches between a read and other reads. Increasing the value would result in more accuracy, however this could slow down the program. The specified value should be more than 0.
 		cap3Commands.add("300"); 
 		cap3Commands.add("-z"); // how many reads support it (small because we removed redundant reads)
 		cap3Commands.add("1");
 		cap3Commands.add("&>cap3_stats.log");
 		
 		// Run the tool
 	    ProcessBuilder cap3PB = new ProcessBuilder(cap3Commands);
        Process cap3Process = cap3PB.start();
         
        // Collect error messages
        ProcessStream errStream = new ProcessStream(cap3Process.getErrorStream(), "ERROR");            
        errStream.start();
         
        // Catch error
        if (errStream.getOutput() != null && !errStream.getOutput().equals("") && errStream.getOutput().length() != 0) {
         	throw new InputParametersException("CAP3 ERROR:\n" + errStream.getOutput());
        } else {
         	errStream.cleanBuffer();
        }
         
         // Collect output
         ProcessStream outputStream = new ProcessStream(cap3Process.getInputStream(), "OUTPUT");
         outputStream.start();
         
         cap3Process.waitFor();
         
         outputStream.cleanBuffer(); // clean buffer
         
         // Return output file name
         return outputDirectory + "/" + IOParameters.ME_TYPE + "." + chromosome + "_" + position + ".cdhit.cap.contigs";
	}
	
	/**
	 * Run cd-hit tool to remove redundant reads before assembly
	 * @param chromosome
	 * @param position
	 * @return a file with reads
	 * @throws InputParametersException
	 * @throws IOException
	 * @throws InterruptedException
	 */
	private static String removeRedundantReads(String chromosome, long position) throws InputParametersException, IOException, InterruptedException {		
		// Setup input file
		String inputFile = System.getProperty("user.dir") + "/disc_reads/" + chromosome + "_" + position + IOParameters.OUTPUT_FORMAT;
		//Setup output directory
		String outputDirectory = System.getProperty("user.dir") + "/intermediate_output/cap3_assembly/" + IOParameters.ME_TYPE + "/" + IOParameters.ME_TYPE + "." + chromosome + "_" + position;
		// Setup output file
		String outputFile = outputDirectory + "/" + IOParameters.ME_TYPE + "." + chromosome + "_" + position + ".cdhit";

		// Check if input file exists
		File input = new File(inputFile);
		if (!input.exists() || input.isDirectory()) {
			return null;
		}
		// Create output directory if it doesn't exist
		Path path = Paths.get(outputDirectory);
        if (!Files.exists(path)) {
           Files.createDirectories(path);
        }
		        
		// Commands for running cd-hit-est
 		List<String> cdhitCommands = new ArrayList<String>();
 		cdhitCommands.add(IOParameters.CDHIT_TOOL_PATH + "/cd-hit-est");
 		cdhitCommands.add("-i");
 		cdhitCommands.add(inputFile);
 		cdhitCommands.add("-c");
 		cdhitCommands.add(IOParameters.PERC_IDENTITY_CDHIT.toString());
 		cdhitCommands.add("-o");
 		cdhitCommands.add(outputFile);

 		// Run the process
 	    ProcessBuilder cdhitPB = new ProcessBuilder(cdhitCommands);
        Process cdhitProcess = cdhitPB.start();
         
        // Collect error messages
        ProcessStream errStream = new ProcessStream(cdhitProcess.getErrorStream(), "ERROR");            
        errStream.start();
         
        // Catch error
        if (errStream.getOutput() != null && !errStream.getOutput().equals("") && errStream.getOutput().length() != 0) {
         	throw new InputParametersException("CDHIT ERROR:\n" + errStream.getOutput());
        } else {
         	errStream.cleanBuffer();
        }
         
        // Collect output
        ProcessStream outputStream = new ProcessStream(cdhitProcess.getInputStream(), "OUTPUT");
        outputStream.start();
         
        cdhitProcess.waitFor();
         
        outputStream.cleanBuffer(); // clean buffer
		
		return outputFile;
	}
	
	/**
	 * Parse a file with contigs into separate files for each contig
	 * @param fileWithContigs - file with contigs
	 * @param contigsDir - destination directory
	 * @return destination directory
	 * @throws IOException
	 */
	public static String parseContigsIntoSepFiles(String fileWithContigs, String contigsDir) throws IOException {
		// Check if input file exists
		if (fileWithContigs == null) {
			return null;
		}
		
		File input = new File(fileWithContigs);
		if (!input.exists() || input.isDirectory()) {
			return null;
		}

		// Collect list of contigs
        FastaParser faParser = new FastaParser(fileWithContigs);
		List<FASTASeq> contigs = faParser.parse();
		
		if (contigs == null || contigs.size() == 0) {
			LOGGER.info("No contigs found in: " + fileWithContigs + "\n");
			return null;
		}
		
		String outfilename = "";
		BufferedWriter outwriter;
		
		// Parse and write each contig into a file
		for (FASTASeq contig : contigs) {
			// Remove too short contigs
			if (contig.getSequence().length() < IOParameters.MIN_CONTIG_LENGTH.get(IOParameters.ME_TYPE)) {
				continue;
			}
			outfilename = contigsDir + "/" + contig.getDescription() + ".fa";

			outwriter = new BufferedWriter(new FileWriter(outfilename));
			
			outwriter.write(contig.toPrint());
			
			outwriter.close();
		}
		
		return contigsDir;
	}
	
}

/*
 * Options (default values):
  -a  N  specify band expansion size N > 10 (20)
  -b  N  specify base quality cutoff for differences N > 15 (20)
  -c  N  specify base quality cutoff for clipping N > 5 (12)
  -d  N  specify max qscore sum at differences N > 20 (200)
  -e  N  specify clearance between no. of diff N > 10 (30)
  -f  N  specify max gap length in any overlap N > 1 (20)
  -g  N  specify gap penalty factor N > 0 (6)
  -h  N  specify max overhang percent length N > 2 (20)
  -i  N  specify segment pair score cutoff N > 20 (40)
  -j  N  specify chain score cutoff N > 30 (80)
  -k  N  specify end clipping flag N >= 0 (1)
  -m  N  specify match score factor N > 0 (2)
  -n  N  specify mismatch score factor N < 0 (-5)
  -o  N  specify overlap length cutoff > 15 (40)
  -p  N  specify overlap percent identity cutoff N > 65 (90)
  -r  N  specify reverse orientation value N >= 0 (1)
  -s  N  specify overlap similarity score cutoff N > 250 (900)
  -t  N  specify max number of word matches N > 30 (300)
  -u  N  specify min number of constraints for correction N > 0 (3)
  -v  N  specify min number of constraints for linking N > 0 (2)
  -w  N  specify file name for clipping information (none)
  -x  N  specify prefix string for output file names (cap)
  -y  N  specify clipping range N > 5 (100)
  -z  N  specify min no. of good reads at clip pos N > 0 (3)
 */