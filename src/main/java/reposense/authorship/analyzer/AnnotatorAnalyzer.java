package reposense.authorship.analyzer;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import reposense.authorship.model.FileInfo;
import reposense.authorship.model.LineInfo;
import reposense.authorship.model.TextBlockInfo;
import reposense.model.Author;
import reposense.model.AuthorConfiguration;

/**
 * Analyzes the authorship of a {@code FileInfo} using the given annotations on the file.
 * Only the lines with the format (START OF LINE) COMMENT_SYMBOL @@author ONE_STRING_WITH_NO_SPACE (END OF LINE)
 * will be analyzed. Otherwise, the line will be ignored and treated as normal lines.
 * If the line is analyzed, and the string following the author tag is a valid git id, and there is no author config
 * file, then the code will be attributed to the author with that git id. Otherwise, the code will be attributed to
 * unknown author
 */
public class AnnotatorAnalyzer {
    private static final String AUTHOR_TAG = "@@author";
    // GitHub username format
    private static final String REGEX_AUTHOR_NAME_FORMAT = "[a-zA-Z0-9](?:[a-zA-Z0-9]|-(?=[a-zA-Z0-9])){0,38}\\s\\d*";
    private static final Pattern PATTERN_AUTHOR_NAME_FORMAT = Pattern.compile(REGEX_AUTHOR_NAME_FORMAT);
    private static final String REGEX_AUTHOR_TAG_FORMAT = "@@author(\\s+[^\\s]+)*";

    private static final String[][] COMMENT_FORMATS = {
            {"//", "\\s"},
            {"/\\*", "\\*/"},
            {"#", "\\s"},
            {"<!--", "-->"},
            {"%", "\\s"},
    };

    private static final Pattern[] COMMENT_PATTERNS = {
            Pattern.compile(generateCommentRegex(COMMENT_FORMATS[0][0], COMMENT_FORMATS[0][1])),
            Pattern.compile(generateCommentRegex(COMMENT_FORMATS[1][0], COMMENT_FORMATS[1][1])),
            Pattern.compile(generateCommentRegex(COMMENT_FORMATS[2][0], COMMENT_FORMATS[2][1])),
            Pattern.compile(generateCommentRegex(COMMENT_FORMATS[3][0], COMMENT_FORMATS[3][1])),
            Pattern.compile(generateCommentRegex(COMMENT_FORMATS[4][0], COMMENT_FORMATS[4][1]))
    };

    /**
     * Overrides the authorship information in {@code fileInfo} based on annotations given on the file.
     */
    public static void aggregateAnnotationAuthorInfo(FileInfo fileInfo, AuthorConfiguration authorConfig) {
        Path filePath = Paths.get(fileInfo.getPath());
        ArrayList<LineInfo> toRemove = new ArrayList<>();
        ArrayList<TextBlockInfo> toAdd = new ArrayList<>();

        boolean foundStartAnnotation = false;
        TextBlockInfo currentBlock = null;

        for (LineInfo lineInfo : fileInfo.getLines()) {
            String lineContent = lineInfo.getContent();
            if (lineContent.contains("@@author")) {
                int formatIndex = checkValidCommentLine(lineContent);
                if (formatIndex >= 0) {
                    Optional<HashMap<Author, Integer>> newAnnotatedAuthors = findAuthorsInLine(lineContent, authorConfig,
                             foundStartAnnotation, formatIndex, filePath);

                    //end author flag
                    if (!newAnnotatedAuthors.isPresent()) {
                        //if preceded by valid start author flag
                        if (foundStartAnnotation) {
                            currentBlock.setEndLineNumber(lineInfo.getLineNumber());
                            currentBlock.addLine(lineInfo);
                            toRemove.add(lineInfo);
//                            fileInfo.removeLine(lineInfo);

                            //add complete block to fileInfo and reset block
                            toAdd.add(currentBlock);
//                            fileInfo.addTextBlock(currentBlock);
                            currentBlock = null;
                            foundStartAnnotation = false;

                        }
                    //start of new block
                    } else {
                        currentBlock = new TextBlockInfo();
                        currentBlock.setContributionMap(newAnnotatedAuthors.get());
                        currentBlock.setStartLineNumber(lineInfo.getLineNumber());
                        currentBlock.addLine(lineInfo);
                        toRemove.add(lineInfo);
//                        fileInfo.removeLine(lineInfo);

                        foundStartAnnotation = true;
                    }
                }
            } else {
                if (foundStartAnnotation) {
                    currentBlock.addLine(lineInfo);
                    toRemove.add(lineInfo);
//                    fileInfo.removeLine(lineInfo);
                }
            }
        }
        fileInfo.removeLines(toRemove);
        fileInfo.addBlocks(toAdd);
    }

    /**
     * Extracts the author name and correspond authorship weight from the given {@code line},
     * finds the corresponding {@code Author} in {@code authorAliasMap}, and returns the author
     * and their authorship weight as a key-value pair in a hashmap stored in an {@code Optional}.
     */
    private static Optional<HashMap<Author, Integer>> findAuthorsInLine(String line, AuthorConfiguration authorConfig,
        boolean foundStartAnnotation, int formatIndex, Path filePath) {
        try {
            Map<String, Author> authorAliasMap = authorConfig.getAuthorDetailsToAuthorMap();
            String authorsParameters = extractAuthorsParameters(line, formatIndex);

            if (authorsParameters == null) {
                if (!foundStartAnnotation) {
                    // Attribute to only unknown author if an empty author tag was provided, but not as an end author tag
                    HashMap<Author, Integer> newAnnotatedAuthors = new HashMap<>();
                    newAnnotatedAuthors.put(Author.UNKNOWN_AUTHOR, 1);
                    return Optional.of(newAnnotatedAuthors);
                }
                return Optional.empty();
            } else {
                Matcher matcher = PATTERN_AUTHOR_NAME_FORMAT.matcher(authorsParameters);
                boolean foundMatch = matcher.find();
                HashMap<Author, Integer> newAnnotatedAuthors = new HashMap<>();

                while(foundMatch) {
                    String parameters = matcher.group();

                    String[] splitParameters = parameters.split(" ");
                    String author = splitParameters[0];
                    Integer weight = Integer.parseInt(splitParameters[1].isEmpty() ? "100" : splitParameters[1]);

                    if (!authorAliasMap.containsKey(author) && !AuthorConfiguration.hasAuthorConfigFile()) {
                        authorConfig.addAuthor(new Author(author));
                    }

                    Author annotatedAuthor = authorAliasMap.getOrDefault(author, Author.UNKNOWN_AUTHOR);

                    if (!annotatedAuthor.isIgnoringFile(filePath)) {
                        newAnnotatedAuthors.put(annotatedAuthor, weight);
                    }

                    foundMatch = matcher.find();
                }

                //no matching parameters
                if (newAnnotatedAuthors.isEmpty()) {
                    newAnnotatedAuthors.put(Author.UNKNOWN_AUTHOR, 1);
                }

                return Optional.of(newAnnotatedAuthors);
            }
        } catch (ArrayIndexOutOfBoundsException e) {
            if (!foundStartAnnotation) {
                HashMap<Author, Integer> newAnnotatedAuthors = new HashMap<>();
                newAnnotatedAuthors.put(Author.UNKNOWN_AUTHOR, 1);
                return Optional.of(newAnnotatedAuthors);
            }
            return Optional.empty();
        }
    }

    /**
     * Extracts the name that follows the specific format.
     *
     * @return an empty string if no such author was found, the new author name otherwise
     */
    public static String extractAuthorsParameters(String line, int formatIndex) {
        String[] splitByAuthorTag = line.split(AUTHOR_TAG);

        if (splitByAuthorTag.length < 2) {
            return null;
        }


        String endComment = COMMENT_FORMATS[formatIndex][1];

        if (Objects.equals(endComment, "\\s")) {
            return splitByAuthorTag[1].trim();
        } else {
            String[] splitByCommentFormat = splitByAuthorTag[1].trim().split(endComment);
            if (splitByCommentFormat.length == 0) {
                return null;
            }
            String authorTagParameters = splitByCommentFormat[0];
            return authorTagParameters.trim();
        }
    }

    private static String generateCommentRegex(String commentStart, String commentEnd) {
        return "^[\\s]*" + commentStart + "[\\s]*" + REGEX_AUTHOR_TAG_FORMAT + "[\\s]*(" + commentEnd + ")?[\\s]*$";
    }

    /**
     * Checks if the line is a valid @@author tag comment line
     * @param line The line to be checked
     * @return The index of the comment if the comment pattern matches, -1 if no match could be found
     */
    public static int checkValidCommentLine(String line) {
        for (int i = 0; i < COMMENT_PATTERNS.length; i++) {
            Pattern commentPattern = COMMENT_PATTERNS[i];
            Matcher matcher = commentPattern.matcher(line);
            if (matcher.find()) {
                return i;
            }
        }
        return -1;
    }
}
