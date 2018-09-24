package formatting.automation;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.Map.Entry;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.filefilter.DirectoryFileFilter;
import org.apache.commons.lang.StringUtils;

import com.google.common.base.Predicate;
import com.google.common.collect.Maps;
import com.google.common.collect.Multiset;
import com.google.common.collect.Sets;

import codemining.cpp.codeutils.CASTAnnotatedTokenizer;
import codemining.cpp.codeutils.CppWhitespaceTokenizer;
import codemining.java.tokenizers.JavaWhitespaceTokenizer;
import codemining.languagetools.FormattingTokenizer;
import codemining.languagetools.ITokenizer.FullToken;
import codemining.lm.ngram.AbstractNGramLM;
import codemining.lm.ngram.NGram;
import codemining.util.SettingsLoader;
import renaming.formatting.FormattingRenamings;
import renaming.renamers.INGramIdentifierRenamer.Renaming;
import renaming.tools.FormattingReviewAssistant;

/**
 * An automated formating tool.
 * 
 * @author Benjamin Loriot <loriotbe@etu.utc.fr>
 * 
 */
public class AutoFormatting {
	
	public static final double CONFIDENCE_THRESHOLD = SettingsLoader
			.getNumericSetting("confidenceThreshold", 10);
	
	private static double getScoreOf(final SortedSet<Renaming> suggestions,
			final String actual) {
		for (final Renaming r : suggestions) {
			if (r.name.equals(actual)) {
				return r.score;
			}
		}
		return Double.MAX_VALUE;
	}
	
	/**
	 * @param args
	 * @throws IOException
	 */
	public static void main(final String[] args) throws IOException {
		if (args.length < 3) {
			System.err.println("Usage -t <trainDirectory> -o <outputDirectory> -f <suggestFilesDir> -exclude <exludeFile>");
			System.exit(-1);
		}
		
		

		File trainDir = null;
		File outputDir = null;
		File fileDir = null;
		File excludeFile = null;
		
		String mode = "normal";
		
		int i = 0;
		while ( i<args.length && args[i].startsWith("-") ) {
			switch ( args[i] ) {
				case "-mode" :
					i++;
					mode  = args[i++];
					break;
				case "-t":
					i++;
					trainDir = new File(args[i++]);
					break;
				case "-o" :
					i++;
					outputDir  = new File(args[i++]);
					break;
				case "-f" :
					i++;
					fileDir  = new File(args[i++]);
					break;
				case "-exclude" :
					i++;
					excludeFile = new File(args[i++]);
					break;
			}
		}
		
		HashMap<File, Integer[] > snipperFiles = new HashMap<File, Integer[] >();
		if ( mode.equals("snipper") ) {
			ArrayList<String> filesWithLines = new ArrayList<String>();
			
			for ( ; i < args.length ; i ++) {
				filesWithLines.add(args[i]);
			}
			for ( String fileWithLine : filesWithLines) {
				String[] fileSplited = fileWithLine.split(":");
				System.out.println(fileSplited[0]);
				System.out.println(fileSplited[1]);
				System.out.println(fileSplited.length);
				if ( fileSplited.length == 2 ) {
					String[] positions = fileSplited[1].split(",");
					snipperFiles.put(new File(fileSplited[0]), new Integer[]{Integer.parseInt(positions[0]), Integer.parseInt(positions[1])} );
				}
			}
		}
		
		/*final ArrayList<File> testFiles = new ArrayList<File>();
		for (int i = 0; i < args.length - 2; i ++) {
			testFiles.add(new File(args[2 + i]));
		}*/
		
		final FormattingTokenizer tokenizer;
		tokenizer = new FormattingTokenizer(new JavaWhitespaceTokenizer());
		
		Collection<File> testFiles = new ArrayList<File>();
		
		if (mode.equals("snipper")) {
			
		} else {
			testFiles = FileUtils.listFiles(fileDir,
					tokenizer.getFileFilter(), DirectoryFileFilter.DIRECTORY);
		}



		final Collection<File> trainFiles = FileUtils.listFiles(trainDir,
				tokenizer.getFileFilter(), DirectoryFileFilter.DIRECTORY);
		
		for ( final File testFile : testFiles ) {
			trainFiles.remove(testFile);
			System.out.println();
		}
		
		if (excludeFile != null) {
			trainFiles.remove(excludeFile);
		}
		
		
		final AutoFormatting reviewer = new AutoFormatting(
				tokenizer, trainFiles);
		
		if ( mode.equals("snipper")) {
			for ( Entry<File, Integer[]> entry : snipperFiles.entrySet() ) {
				File testFile = entry.getKey();
				Integer[] interval = entry.getValue();
				final String res = reviewer.reviewFile(testFile, interval[0], interval[1]);
				
				try {
					File file = new File(outputDir + testFile.toString().substring( (int)fileDir.toString().length() ));
					file.getParentFile().mkdirs();
					BufferedWriter writer = new BufferedWriter(new FileWriter(file));
				    writer.write(res);
				     
				    writer.close();
				} catch (Exception err) {
					System.out.print(err.getMessage());
				}
			}
		} else {
			for ( final File testFile : testFiles ) {
				final String res = reviewer.reviewFile(testFile);
				
				try {
					File file = new File(outputDir + testFile.toString().substring( (int)fileDir.toString().length() ));
					file.getParentFile().mkdirs();
					BufferedWriter writer = new BufferedWriter(new FileWriter(file));
				    writer.write(res);
				     
				    writer.close();
				} catch (Exception err) {
					System.out.print(err.getMessage());
				}
			}
		}

	}
	
	private final FormattingRenamings renamings;

	private final FormattingTokenizer tokenizer;

	private final Set<String> alternatives;
	
	/**
	 * 
	 */
	public AutoFormatting(final FormattingTokenizer tokenizer,
			final Collection<File> trainFiles) {
		this.tokenizer = tokenizer;
		renamings = new FormattingRenamings(tokenizer);
		renamings.buildModel(trainFiles);
		alternatives = Sets.newHashSet(getAlternativeNamings());
	}
	
	private String reviewFile(final File testFile) throws IOException {
		return reviewFile(testFile, -1, -1);
	}
	
	private String reviewFile(final File testFile, int from, int to) throws IOException {
		final String testSourceFile = FileUtils.readFileToString(testFile);
		// probably a better option... 
		
		final List<String> tokens = renamings.tokenizeCode(testSourceFile
				.toCharArray());
		final SortedMap<Integer, SortedSet<Renaming>> suggestedRenamings = Maps
				.newTreeMap();

		for (int i = 0; i < tokens.size(); i++) {
			if ( tokens.get(i).equals("IDENTIFIER")) {
				
			} else {
				System.out.println(tokens.get(i));
			}
			if (tokens.get(i).startsWith("WS_")) { // WS_ stands for white space 
				// create all n-grams around i
				final Multiset<NGram<String>> ngrams = renamings
						.getNGramsAround(i, tokens);
				
				// score accuracy of first suggestion
				final SortedSet<Renaming> suggestions = renamings
						.calculateScores(ngrams, alternatives, null);
				final String actual = tokens.get(i);
				if (suggestions.first().name.equals(AbstractNGramLM.UNK_SYMBOL)
						|| suggestions.first().name.equals(actual)) {
					continue;
				}

				final double actualScore = getScoreOf(suggestions, actual);
				if (actualScore - suggestions.first().score > CONFIDENCE_THRESHOLD) {
					//System.out.println(i + ":" + suggestions.first().name + "/" + fullTokens.get(i).token);
					//System.out.println("context" + fullTokens.get(i - 1).token + " " + fullTokens.get(i).token + " " + fullTokens.get(i + 1).token);
					//System.out.println(suggestions);
					suggestedRenamings.put(i, suggestions);
				}
			}
		}
	
		if ( from > 0 && to > 0) {
			return getStringFormated(testSourceFile, suggestedRenamings, from, to);
		}
		return getStringFormated(testSourceFile, suggestedRenamings);
	}
	
	/**
	 * @param fr
	 * @return
	 */
	public Set<String> getAlternativeNamings() {
		final Set<String> alternatives = Sets.newTreeSet(Sets.filter(renamings
				.getNgramLM().getTrie().getVocabulary(),
				new Predicate<String>() {

					@Override
					public boolean apply(final String input) {
						return input.startsWith("WS_");
					}
				}));
		alternatives.add(AbstractNGramLM.UNK_SYMBOL);
		return alternatives;
	}
	
	private String getStringFormated(
			final String testSourceFile,
			final SortedMap<Integer, SortedSet<Renaming>> suggestedRenamings) {
		return getStringFormated(testSourceFile, suggestedRenamings, -1, testSourceFile.length() + 1);
	}
	
	private String getStringFormated(
			final String testSourceFile,
			final SortedMap<Integer, SortedSet<Renaming>> suggestedRenamings, int from, int to) {
		StringBuffer result = new StringBuffer(10000);
		final List<String> tokensPos = tokenizer
				.tokenListFromCode(testSourceFile.toCharArray());

		
		
		int i = 0;
		
		Object[] tokens = tokenizer.getBaseTokenizer()
				.tokenListWithPos(testSourceFile.toCharArray()).entrySet().toArray();
		//System.out.println(tokens);
		for (int index = 0; index < tokens.length - 2; index ++) {
			Entry<Integer, String> token = (Entry<Integer, String>)tokens[index];
			Entry<Integer, String> nextToken = (Entry<Integer, String>)tokens[index + 1];
			
			if (suggestedRenamings.containsKey(i) && nextToken.getKey() > from && token.getKey() < to) {
				Renaming newToken = suggestedRenamings.get(i).first();
				//System.out.print(newToken.name);
				if ( newToken.name.startsWith("WS_DEDENT") || newToken.name.startsWith("WS_INDENT")) {
					String format = newToken.name.replace("WS_DEDENT", "");
					int index_s = format.indexOf('s');
					int index_t = format.indexOf('t');
					int index_n = format.indexOf('n');
					
					int s = Integer.parseInt(format.substring(index_s + 1, index_t));
					int t = Integer.parseInt(format.substring(index_t + 1, index_n));
					int n = Integer.parseInt(format.substring(index_n + 1));
					result.append(StringUtils.repeat("\n", n));
					result.append(StringUtils.repeat("\t", t));
					result.append(StringUtils.repeat(" ", s));
				} else if ( newToken.name.startsWith("WS_NO_SPACE") ) {
					
				} else if ( newToken.name.startsWith("WS_s") ) {
					int index_s = newToken.name.indexOf('s');
					int index_t = newToken.name.indexOf('t');
					
					int s = Integer.parseInt(newToken.name.substring(index_s + 1, index_t));
					int t = Integer.parseInt(newToken.name.substring(index_t + 1));
					
					result.append(StringUtils.repeat(" ", s));
					result.append(StringUtils.repeat("\t", t));
					
					
				} else {
					result.append(newToken.name);
				}
				if (tokensPos.get(i).equals(FormattingTokenizer.WS_NO_SPACE)) {
					result.append(testSourceFile.substring(token.getKey(), nextToken.getKey()));
				}
			} else { 
				if (token.getKey() != -1 ) {
					//System.out.println(token);
					result.append(testSourceFile.substring(token.getKey(), nextToken.getKey()));
					
				}
			}
			
			if (tokensPos.get(i).equals(FormattingTokenizer.WS_NO_SPACE)) {
				i++;
			}
			i++;
		}
		//System.out.print(testSourceFile.substring(((Entry<Integer, String>)tokens[tokens.length - 1]).getKey()));
		return result.toString();
	}
	

}
