package com.yg;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.logging.*;

import org.apache.commons.io.FileUtils;

import com.yg.assembler.BLASTAlignment;
import com.yg.assembler.CAP3Assembler;
import com.yg.assembler.BridgeAssembly;
import com.yg.exceptions.InputParametersException;
import com.yg.io_handlers.IOParameters;
import com.yg.io_handlers.InputDataHandler;
import com.yg.io_handlers.OutputData;
import com.yg.io_handlers.UserInputHandler;
import com.yg.logger.CustomLogger;
import com.yg.models.FASTASeq;
import com.yg.models.MEInsertion;
import com.yg.models.Variants;
import com.yg.parsers.FastaParser;
import com.yg.utilities.IOGeneralHelper;

/**
 * Main class for running pipeline for MEIs characterization 
 * 
 * @author Yaroslava Girilishena
 *
 */
public class Main {
	
	public final static Logger LOGGER = Logger.getLogger(Logger.GLOBAL_LOGGER_NAME);

	/**
	 * Main method that starts the process
	 * @param args
	 */
	public static void main(String[] args) {
		// Setup logger
		try {
			CustomLogger.setup(System.getProperty("user.dir") + "/log/start.log");
		} catch (IOException e1) {
			e1.printStackTrace();
			return;
		}
		
		// Check the input parameters
		try {
			if (!UserInputHandler.parseCLParameters(args)) {
				return;
			}
		} catch (InputParametersException e) {
			LOGGER.log(Level.SEVERE, "EXCEPTION:\n" + e.toString(), e);
			UserInputHandler.printHelp();
			e.printStackTrace();
			return;
		}
		
		try {
			// Initialize IO handler
			InputDataHandler ioHandler = new InputDataHandler();
			
			// Process input
			inputPreprocessing(ioHandler);
			
			// Run the pipeline
			runThePipeline(ioHandler);
			
		} catch (Exception e) {
			LOGGER.log(Level.SEVERE, "EXCEPTION:\n" + e.toString(), e);
			UserInputHandler.printHelp();
			e.printStackTrace();
			return;
		}
	}
	
	/**
	 * 
	 * @param ioHandler
	 * @throws IOException
	 * @throws InputParametersException 
	 * @throws InterruptedException 
	 */
	public static void inputPreprocessing(InputDataHandler ioHandler) throws IOException, InputParametersException, InterruptedException {
		// Setup file for logging
		LOGGER.info(Main.class.getName() + " LOGGER STARTED \n");

		// Index blast databases
		BLASTAlignment.formatDB();
		
		// Parse RepeatMasker output into files that store each subtype data separately 
		//BEDParser.parseByTypes(IOParameters.REPEATMASK_LOCS_BED + IOParameters.ME_TYPE + IOParameters.REFERENCE_END);
		
		if (IOParameters.INPUT_FILE) {
			// Process different input file formats
			if (IOParameters.INPUT_FILE_WITH_LOCATIONS.endsWith("bed")) {
				// Parse and process BED file
				ioHandler.parseNonRefMEIs(IOParameters.INPUT_FILE_WITH_LOCATIONS); // IOParameters.NON_REF_MEIS + IOParameters.ME_TYPE + ".chr10.bed");
			} else if (IOParameters.INPUT_FILE_WITH_LOCATIONS.endsWith("vcf")) {
				// Parse and process VCF file
				ioHandler.parseVCF(IOParameters.INPUT_FILE_WITH_LOCATIONS); // IOParameters.VCF_INPUT_FILE);
			} else {
				throw new InputParametersException("Input file format is not supported");
			}
		} else {
			Variants.listOfMEI.add(new MEInsertion(IOParameters.DEF_CHROMOSOME, IOParameters.DEF_POSITION, IOParameters.DEF_POSITION));
		}
	}
	/**
	 * 
	 * @param typeIndex
	 * @param ioHandler
	 * @throws Exception 
	 */
	public static void runThePipeline(InputDataHandler ioHandler) throws Exception {
		// For each variant
		LOGGER.info("LIST OF MEIs: " + Variants.listOfMEI.size() + "\n");
		
		if (!IOParameters.SE_SPECIFIED) {
			IOParameters.START_LOCI = 0;
			IOParameters.END_LOCI =  Variants.listOfMEI.size();
		}
		int i = 0;
		for (MEInsertion me: Variants.listOfMEI.subList(IOParameters.START_LOCI, IOParameters.END_LOCI)) {
			// Setup log file for each event
			CustomLogger.setup(System.getProperty("user.dir") + "/log/" + IOParameters.ME_TYPE + "." + me.getChromosome() + "_" + me.getPosition() + ".log");
			
			LOGGER.info("\n---------------------------------------------------------------------------------------------------------------------------------\n" +
					"---------------------------------------------------------------------------------------------------------------------------------\n");
			LOGGER.info("Process STARTED for: " + IOParameters.ME_TYPE + "." + me.getChromosome() + '_' + me.getPosition() + "\t#" + i + " in a list\n");
			i++;
			
			// Collect discordant and split-reads by running SAM tool on the collection of .bam files
			Integer estimatedCoverage = 0;
			if (IOParameters.COLLECT_READS) {
				// Check if reads are collected for given location
				String readsFile = System.getProperty("user.dir") + "/disc_reads/" + me.getChromosome() + '_' + me.getPosition() + ".fa";
				File input = new File(readsFile);
				
				// If file with reads not exist, run SAM tool
				if (!input.exists() || input.isDirectory()) {
					estimatedCoverage = ioHandler.collectDiscordantReads(me.getChromosome(), me.getPosition());
					if (estimatedCoverage < 2) { 
						LOGGER.info("Estimated COVERAGE: " + estimatedCoverage + " - not qualified \n");
						continue; 
					} else {
						LOGGER.info("Estimated COVERAGE: " + estimatedCoverage + "\n");
					}
				}
			}
			
			// Perform local assembly on collected reads using cap3 assembler
			String contigsFA = CAP3Assembler.doAssembly(me.getChromosome(), me.getPosition());
			if (contigsFA == null || contigsFA.equals("")) {
				LOGGER.info("No ASSEMBLED contigs");
				OutputData.writeFailedMEOut(me); // write down failed mei
				continue;
			}
			
			// Check obtained contigs after the assembly
			Map<String, FASTASeq> contigs = FastaParser.extractContigs(contigsFA);
			if (contigs == null || contigs.isEmpty()) {
				LOGGER.info("NO CONTIGS ASSEMBLED for " + me.getChromosome() + ":" + me.getPosition() + "\n");
				OutputData.writeFailedMEOut(me); // write down failed mei
				continue;
			}
						
			// Parse contigs into separate files
			String contigsOutDir = "/intermediate_output/contigs_for_merging/" + IOParameters.ME_TYPE + "/" + IOParameters.ME_TYPE + "." + me.getChromosome() + "_" + me.getPosition() + "/contigs"; //contigsFA.substring(0, contigsFA.lastIndexOf("/")) + "/separated_contigs";
			// Create new directory
			IOGeneralHelper.createOutDir(contigsOutDir);
			// Remove all existing files from that directory
			FileUtils.cleanDirectory(new File(System.getProperty("user.dir") + contigsOutDir));
			
			String contigsDir = CAP3Assembler.parseContigsIntoSepFiles(contigsFA, System.getProperty("user.dir") + contigsOutDir);
			if (contigsDir == null) {
				throw new InputParametersException("Contigs cannot be separated into files");
			}		
						
			// Run Bridge assembly on obtained separated contigs
			BridgeAssembly bridgeAssebly = new BridgeAssembly(me);
			String mergedContigsFile = bridgeAssebly.findMaxOverlap(contigsDir);
			
			// Check if merged contigs file exist
			if (mergedContigsFile == null) {
				LOGGER.info("NO MERGED contigs file\n");
				OutputData.writeFailedMEOut(me); // write down failed mei
				continue;
			}
			File input = new File(mergedContigsFile);
			if (!input.exists() || input.isDirectory()) {
				LOGGER.info("NO MERGED contigs file\n");
				OutputData.writeFailedMEOut(me); // write down failed mei
				continue;
			}
			
			// Check obtained contigs after assembly
			contigs = FastaParser.extractContigs(mergedContigsFile);
			if (contigs == null || contigs.isEmpty()) {
				LOGGER.info("NO MERGED contigs\n");
				OutputData.writeFailedMEOut(me); // write down failed mei
				
				LOGGER.info("\nProcess ENDED for: " + IOParameters.ME_TYPE + "." + me.getChromosome() + "_" + me.getPosition() + "\n");
				LOGGER.info("\n---------------------------------------------------------------------------------------------------------------------------------\n" +
						"---------------------------------------------------------------------------------------------------------------------------------\n\n");

				continue;
			}
			
			// Set all contigs to MEI
			for (String contigKey : contigs.keySet()) {
				me.getContigs().add(contigs.get(contigKey)); // add all contigs
			}
			
			// Generate output
			if (me.isFull() || me.isPartialChar()) { // insertion is fully or partially characterized
				
				OutputData.extractTSD(mergedContigsFile, me); // get TSD or IMD
				OutputData.writeMEOut(me); // write MEI and pre-integration sequence into a file
				
			} else { // no full insertion constructed
				OutputData.writeFailedMEOut(me); // write failed MEI to a separate file
			}
			
			LOGGER.info("\nProcess ENDED for: " + IOParameters.ME_TYPE + "." + me.getChromosome() + "_" + me.getPosition() + "\n");
			LOGGER.info("\n---------------------------------------------------------------------------------------------------------------------------------\n" +
					"---------------------------------------------------------------------------------------------------------------------------------\n\n");

			// Close log file
			CustomLogger.fileTxt.close();
			
			// -------------------------------------------
			// FUTURE WORK FOR LONG INSERTIONS
			// -------------------------------------------
			
			// Obtain positions in ref sequence 			
			// Collect concordant reads from reference
			// Do bridge assembly to fill in gaps in the middle
			// Align full sequence to consensus
		}	
	}
}