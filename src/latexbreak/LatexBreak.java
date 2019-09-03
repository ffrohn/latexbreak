package latexbreak;

import static java.util.stream.Collectors.*;

import java.io.*;
import java.util.*;
import java.util.stream.*;

import com.florianingerl.util.regex.*;

/**
 * Cannot handle $$...$$ -- use \[...\] instead.
 */
public class LatexBreak {

    /**
     * lines of the document, mutable
     */
    private ArrayList<LatexLine> lines;
    /**
     * replacement map that specifies where to insert line breaks
     */
    private Map<Pattern, String> replacements = new LinkedHashMap<>();

    public static String newline = System.lineSeparator();
    /**
     * matches nested curly brackets
     */
    private static String curly = "(?<curly>\\{(?:[^\\{\\}]+|(?'curly'))*+\\})";
    /**
     * matches nested squared brackets
     */
    private static String squared = "(?<squared>\\[(?:[^\\[\\]]+|(?'squared'))*+\\])";
    /**
     * matches consecutive argument lists with squared or curly brackets
     */
    private static String args = "(?<args>\\s|((" + curly + ")|(" + squared + "))+)";

    private static String slash = "\\\\";
    /**
     * matches everything but slashes -- used, for example, to avoid matching the "\[" in "\\[4cm]"
     */
    private static String noSlash = "(?<noslash>[^" + slash + "]|^)";

    private String beginProtected;

    private String endProtected;

    private String beginProtectSentences;

    private String endProtectSentences;

    private Set<Character> lineEnds;

    private Pattern beginProtectSentencesInline;

    private Pattern endProtectSentencesInline;

    private boolean removeNewlines = true;

    private boolean breakAfterSentences = true;

    private String protectBreakAfter;

    private String protectBreakBefore;

    public void breakAroundMacro(String command) {
        replacements.put(Pattern.compile(noSlash + slash + command + args), "${noslash}" + newline + slash + command + "${args}" + newline);
    }

    public void breakAfterMacro(String command) {
        replacements.put(Pattern.compile(noSlash + slash + command + args), "${noslash}" + slash + command + "${args}" + newline);
    }

    public void breakBeforeMacro(String command) {
        replacements.put(Pattern.compile(noSlash + slash + command + args), "${noslash}" + newline + slash + command + "${args}");
    }

    public static void main(String[] args) throws IOException {
        assert args.length == 1;
        var lines = new ArrayList<String>();
        try (var in = new BufferedReader(new FileReader(args[0]))) {
            while (in.ready()) {
                lines.add(in.readLine());
            }
        }
        System.out.println(new LatexBreak(lines).process());
    }

    private void parseConfig() throws IOException {
        try (var in = new BufferedReader(new FileReader(System.getProperty("user.dir") + File.separator + "latexbreak.config"))) {
            while (in.ready()) {
                var line = in.readLine();
                if (line.startsWith("#")) {
                    continue;
                }
                var value = line.split(":")[1].trim();
                var values = value.split(",");
                for (var i = 0; i < values.length; i++) {
                    values[i] = values[i].trim();
                }
                if (line.startsWith("break_around_macro:")) {
                    for (var val: values) {
                        breakAroundMacro(val);
                    }
                } else if (line.startsWith("break_after_macro:")) {
                    for (var val: values) {
                        breakAfterMacro(val);
                    }
                } else if (line.startsWith("break_before_macro:")) {
                    for (var val: values) {
                        breakBeforeMacro(val);
                    }
                } else if (line.startsWith("remove_newlines:")) {
                    removeNewlines = Boolean.parseBoolean(value);
                }
                else if (line.startsWith("break_after_sentences:")) {
                    breakAfterSentences = Boolean.parseBoolean(value);
                } else if (line.startsWith("begin_protect:")) {
                    beginProtected = value;
                } else if (line.startsWith("end_protect:")) {
                    endProtected = value;
                } else if (line.startsWith("protect_break_after:")) {
                    protectBreakAfter = value;
                } else if (line.startsWith("protect_break_before:")) {
                    protectBreakBefore = value;
                } else if (line.startsWith("begin_protect_sentences:")) {
                    beginProtectSentences = value;
                } else if (line.startsWith("end_protect_sentences:")) {
                    endProtectSentences = value;
                } else if (line.startsWith("line_ends:")) {
                    assert(Stream.of(values).allMatch(x -> x.length() == 1));
                    lineEnds = Stream.of(values).map(x -> x.charAt(0)).collect(toSet());
                } else if (line.startsWith("begin_protect_sentences_inline:")) {
                    beginProtectSentencesInline = Pattern.compile("(?<begin_protect_sentences_inline>" + value + ").*");
                } else if (line.startsWith("end_protect_sentences_inline:")) {
                    endProtectSentencesInline = Pattern.compile("(?<end_protect_sentences_inline>" + value + ").*");
                } else {
                    assert false;
                }
            }
        }
    }

    public String process() {
        detectVerbatim();
        trim();
        if (removeNewlines) {
            removeDuplicateBlankLines();
            removeLinebreaks();
        }
        addLinebreaks();
        detectMath();
        if (breakAfterSentences) {
            splitSentences();
        }
        trim();
        removeDuplicateBlanks();
        StringBuilder res = new StringBuilder();
        for (var line: lines) {
            res.append(line);
            res.append(newline);
        }
        return res.toString();
    }

    private void removeDuplicateBlanks() {
        for (var line: lines) {
            while (line.content.contains("  ")) {
                line.content = line.content.replaceAll("  ", " ");
            }
        }
    }

    public LatexBreak(ArrayList<String> lines) throws IOException {
        parseConfig();
        this.lines = lines.stream().map(LatexLine::new).collect(toCollection(ArrayList::new));
    }

    private void splitSentences() {
        var res = new ArrayList<LatexLine>();
        for (var line: lines) {
            if (line.protectSentences || line.protect || line.isBlank()) {
                res.add(line);
            } else {
                boolean math = false;
                var lastSentenceEnd = -1;
                for (var i = 0; i < line.length(); i++) {
                    if (math) {
                        int characters = endsProtectSentencesInline(line, i);
                        if (characters > 0) {
                            math = false;
                            i += characters - 1;
                        }
                    } else {
                        int characters = startsProtectSentencesInline(line, i);
                        if (characters > 0) {
                            math = true;
                            i += characters - 1;
                        } else if (line.length() > i+1 && lineEnds.contains(line.charAt(i)) && line.charAt(i+1) == ' ') {
                            res.add(line.clone().substring(lastSentenceEnd + 1, i + 1).strip());
                            res.add(line.clone().setContent("%"));
                            lastSentenceEnd = i;
                        }
                    }
                }
                var lastLine = line.clone().substring(lastSentenceEnd + 1, line.length());
                if (!lastLine.isBlank()) {
                    res.add(lastLine);
                } else {
                    res.remove(res.size() - 1);
                }
            }
        }
        lines = res;
    }

    private int endsProtectSentencesInline(LatexLine line, int i) {
        var matcher = endProtectSentencesInline.matcher(line.clone().substring(i, line.length()).content);
        if (matcher.matches()) {
            return matcher.group("end_protect_sentences_inline").length();
        } else {
            return -1;
        }
    }

    private int startsProtectSentencesInline(LatexLine line, int i) {
        var matcher = beginProtectSentencesInline.matcher(line.clone().substring(i, line.length()).content);
        if (matcher.matches()) {
            return matcher.group("begin_protect_sentences_inline").length();
        } else {
            return -1;
        }
    }

    private void detectVerbatim() {
        int depth = 0;
        for (var line: lines) {
            if (line.matches(beginProtected)) {
                depth++;
            }
            if (depth > 0) {
                line.protect = true;
            }
            if (line.matches(endProtected)) {
                depth--;
                assert depth >= 0;
            }
        }
    }

    private void detectMath() {
        int depth = 0;
        for (var line: lines) {
            if (line.matches(beginProtectSentences)) {
                depth++;
            }
            if (depth > 0) {
                line.protectSentences = true;
            }
            if (line.matches(endProtectSentences)) {
                depth--;
                assert depth >= 0;
            }
        }
    }

    private void trim() {
        for (var line: lines) {
            if (!line.protect) {
                line.strip();
            }
        }
    }

    private void removeLinebreaks() {
        for (var i = 0; i < lines.size() - 1;) {
            if (!lines.get(i).isBlank()                          // do not touch blank lines -- duplicate blank lines have been removed before
                    && !lines.get(i+1).isBlank()
                    && !lines.get(i).matches(protectBreakAfter)
                    && !lines.get(i + 1).matches(protectBreakBefore)
                    && !lines.get(i).protect                     // do not touch verbatim lines
                    && !lines.get(i + 1).protect
                    && !lines.get(i).matches(".*(^|[^\\\\])%.*") // do nothing if the first line contain comments (but don't be fooled by \%)
                    && !lines.get(i + 1).startsWith("%")) {      // do not touch line comments
                lines.get(i).append(" ").concat(lines.get(i + 1)).strip();;
                lines.remove(i + 1);
            } else {
                i++;
            }
        }
    }

    private void removeDuplicateBlankLines() {
        var lastEmpty = false;
        for (var i = 0; i < lines.size(); i++) {
            var line = lines.get(i);
            if (!line.protect) {
                if (lastEmpty && line.isBlank()) {
                    lines.remove(i);
                    i--;
                } else {
                    lastEmpty = line.isBlank();
                }
            }
        }
    }

    private void addLinebreaks() {
        var res = new ArrayList<LatexLine>();
        // keep old blank lines...
        for (var line: lines) {
            if (line.isBlank() || line.protect) {
                res.add(line);
            } else {
                for (var e: replacements.entrySet()) {
                    var matcher = e.getKey().matcher(line.content);
                    line.content = matcher.replaceAll(e.getValue()).strip();
                }
                for (var s: line.split(newline)) {
                    // ... but don't introduce new blank lines
                    if (!s.isBlank()) {
                        res.add(s.strip());
                    }
                }
            }
        }
        lines = res;
    }

}
