
import java.util.*;
import java.io.IOException;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.Arrays;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.lang3.StringEscapeUtils;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import java.lang.reflect.Method;
public class WdlParser {
    private static Map<Integer, List<TerminalIdentifier>> nonterminal_first;
    private static Map<Integer, List<TerminalIdentifier>> nonterminal_follow;
    private static Map<Integer, List<TerminalIdentifier>> rule_first;
    private static Map<Integer, List<String>> nonterminal_rules;
    private static Map<Integer, String> rules;
    public static WdlTerminalMap terminal_map = new WdlTerminalMap(WdlTerminalIdentifier.values());
    public WdlParser() {
        try {
            lexer_init();
        } catch(Exception e) {}
    }
    public static String join(Collection<?> s, String delimiter) {
        StringBuilder builder = new StringBuilder();
        Iterator iter = s.iterator();
        while (iter.hasNext()) {
            builder.append(iter.next());
            if (!iter.hasNext()) {
                break;
            }
            builder.append(delimiter);
        }
        return builder.toString();
    }
    public static String getIndentString(int spaces) {
        StringBuilder sb = new StringBuilder();
        for(int i = 0; i < spaces; i++) {
            sb.append(' ');
        }
        return sb.toString();
    }
    public static String readStdin() throws IOException {
        InputStreamReader stream = new InputStreamReader(System.in, "utf-8");
        char buffer[] = new char[System.in.available()];
        try {
            stream.read(buffer, 0, System.in.available());
        } finally {
            stream.close();
        }
        return new String(buffer);
    }
    public static String readFile(String path) throws IOException {
        FileInputStream stream = new FileInputStream(new File(path));
        try {
            FileChannel fc = stream.getChannel();
            MappedByteBuffer bb = fc.map(FileChannel.MapMode.READ_ONLY, 0, fc.size());
            /* Instead of using default, pass in a decoder. */
            return Charset.defaultCharset().decode(bb).toString();
        }
        finally {
            stream.close();
        }
    }
    public static class SyntaxError extends Exception {
        public SyntaxError(String message) {
            super(message);
        }
    }
    public interface SyntaxErrorFormatter {
        /* Called when the parser runs out of tokens but isn't finished parsing. */
        String unexpectedEof(String method, List<TerminalIdentifier> expected, List<String> nt_rules);
        /* Called when the parser finished parsing but there are still tokens left in the stream. */
        String excessTokens(String method, Terminal terminal);
        /* Called when the parser is expecting one token and gets another. */
        String unexpectedSymbol(String method, Terminal actual, List<TerminalIdentifier> expected, String rule);
        /* Called when the parser is expecing a tokens but there are no more tokens. */
        String noMoreTokens(String method, TerminalIdentifier expecting, Terminal last);
        /* Invalid terminal is found in the token stream. */
        String invalidTerminal(String method, Terminal invalid);
        /* For lists that have a minimum required size which is not met */
        String missingListItems(String method, int required, int found, Terminal last);
        /* For lists that require a terminal to terminate each element in the list */
        String missingTerminator(String method, TerminalIdentifier terminator, Terminal last);
    }
    public static class TokenStream extends ArrayList<Terminal> {
        private int index;
        public TokenStream(List<Terminal> terminals) {
            super(terminals);
            reset();
        }
        public TokenStream() {
            reset();
        }
        public void reset() {
            this.index = 0;
        }
        public Terminal advance() {
            this.index += 1;
            return this.current();
        }
        public Terminal current() {
            try {
                return this.get(this.index);
            } catch (IndexOutOfBoundsException e) {
                return null;
            }
        }
        public Terminal last() {
          return this.get(this.size() - 1);
        }
    }
    public static class NonTerminal {
        private int id;
        private String string;
        NonTerminal(int id, String string) {
            this.id = id;
            this.string = string;
        }
        public int getId() {
            return this.id;
        }
        public String getString() {
            return this.string;
        }
        public String toString() {
            return this.string;
        }
    }
    public interface AstTransform {}
    public static class AstTransformNodeCreator implements AstTransform {
        private String name;
        private LinkedHashMap<String, Integer> parameters;
        AstTransformNodeCreator(String name, LinkedHashMap<String, Integer> parameters) {
            this.name = name;
            this.parameters = parameters;
        }
        public Map<String, Integer> getParameters() {
            return this.parameters;
        }
        public String getName() {
            return this.name;
        }
        public String toString() {
            LinkedList<String> items = new LinkedList<String>();
            for (final Map.Entry<String, Integer> entry : this.parameters.entrySet()) {
                items.add(entry.getKey() + "=$" + entry.getValue().toString());
            }
            return "AstNodeCreator: " + this.name + "( " + join(items, ", ") + " )";
        }
    }
    public static class AstTransformSubstitution implements AstTransform {
        private int index;
        AstTransformSubstitution(int index) {
            this.index = index;
        }
        public int getIndex() {
            return this.index;
        }
        public String toString() {
            return "AstSubstitution: $" + Integer.toString(this.index);
        }
    }
    public interface AstNode {
        public String toString();
        public String toPrettyString();
        public String toPrettyString(int indent);
    }
    public static class AstList extends ArrayList<AstNode> implements AstNode {
        public String toString() {
            return "[" + join(this, ", ") + "]";
        }
        public String toPrettyString() {
            return toPrettyString(0);
        }
        public String toPrettyString(int indent) {
            String spaces = getIndentString(indent);
            if (this.size() == 0) {
                return spaces + "[]";
            }
            ArrayList<String> elements = new ArrayList<String>();
            for ( AstNode node : this ) {
                elements.add(node.toPrettyString(indent + 2));
            }
            return spaces + "[\n" + join(elements, ",\n") + "\n" + spaces + "]";
        }
    }
    public static class Ast implements AstNode {
        private String name;
        private Map<String, AstNode> attributes;
        Ast(String name, Map<String, AstNode> attributes) {
            this.name = name;
            this.attributes = attributes;
        }
        public AstNode getAttribute(String name) {
            return this.attributes.get(name);
        }
        public Map<String, AstNode> getAttributes() {
            return this.attributes;
        }
        public String getName() {
            return this.name;
        }
        public String toString() {
            Formatter formatter = new Formatter(new StringBuilder(), Locale.US);
            LinkedList<String> attributes = new LinkedList<String>();
            for (final Map.Entry<String, AstNode> attribute : this.attributes.entrySet()) {
                final String name = attribute.getKey();
                final AstNode node = attribute.getValue();
                final String nodeStr = (node == null) ? "None" : node.toString();
                attributes.add(name + "=" + nodeStr);
            }
            formatter.format("(%s: %s)", this.name, join(attributes, ", "));
            return formatter.toString();
        }
        public String toPrettyString() {
            return toPrettyString(0);
        }
        public String toPrettyString(int indent) {
            String spaces = getIndentString(indent);
            ArrayList<String> children = new ArrayList<String>();
            for( Map.Entry<String, AstNode> attribute : this.attributes.entrySet() ) {
                String valueString = attribute.getValue() == null ? "None" : attribute.getValue().toPrettyString(indent + 2).trim();
                children.add(spaces + "  " + attribute.getKey() + "=" + valueString);
            }
            return spaces + "(" + this.name + ":\n" + join(children, ",\n") + "\n" + spaces + ")";
        }
    }
    public interface ParseTreeNode {
        public AstNode toAst();
        public String toString();
        public String toPrettyString();
        public String toPrettyString(int indent);
    }
    public static class Terminal implements AstNode, ParseTreeNode
    {
        public int id;
        public String terminal_str;
        public String source_string;
        public String resource;
        public int line;
        public int col;
        public Terminal(int id, String terminal_str, String source_string, String resource, int line, int col) {
            this.id = id;
            this.terminal_str = terminal_str;
            this.source_string = source_string;
            this.resource = resource;
            this.line = line;
            this.col = col;
        }
        public int getId() {
            return this.id;
        }
        public String getTerminalStr() {
            return this.terminal_str;
        }
        public String getSourceString() {
            return this.source_string;
        }
        public String getResource() {
            return this.resource;
        }
        public int getLine() {
            return this.line;
        }
        public int getColumn() {
            return this.col;
        }
        public String toString() {
            byte[] source_string_bytes;
            try {
                source_string_bytes = this.getSourceString().getBytes("UTF-8");
            } catch (java.io.UnsupportedEncodingException e) {
                source_string_bytes = this.getSourceString().getBytes();
            }
            return String.format("<%s:%d:%d %s \"%s\">",
                this.getResource(),
                this.getLine(),
                this.getColumn(),
                this.getTerminalStr(),
                Base64.encodeBase64String(source_string_bytes)
            );
        }
        public String toPrettyString() {
            return toPrettyString(0);
        }
        public String toPrettyString(int indent) {
            return getIndentString(indent) + this.toString();
        }
        public AstNode toAst() { return this; }
    }
    public static class ParseTree implements ParseTreeNode {
        private NonTerminal nonterminal;
        private ArrayList<ParseTreeNode> children;
        private boolean isExpr, isNud, isPrefix, isInfix, isExprNud;
        private int nudMorphemeCount;
        private int listSeparatorId;
        private boolean list;
        private AstTransform astTransform;
        ParseTree(NonTerminal nonterminal) {
            this.nonterminal = nonterminal;
            this.children = new ArrayList<ParseTreeNode>();
            this.astTransform = null;
            this.isExpr = false;
            this.isNud = false;
            this.isPrefix = false;
            this.isInfix = false;
            this.isExprNud = false;
            this.nudMorphemeCount = 0;
            this.listSeparatorId = -1;
            this.list = false;
        }
        public void setExpr(boolean value) { this.isExpr = value; }
        public void setNud(boolean value) { this.isNud = value; }
        public void setPrefix(boolean value) { this.isPrefix = value; }
        public void setInfix(boolean value) { this.isInfix = value; }
        public void setExprNud(boolean value) { this.isExprNud = value; }
        public void setAstTransformation(AstTransform value) { this.astTransform = value; }
        public void setNudMorphemeCount(int value) { this.nudMorphemeCount = value; }
        public void setList(boolean value) { this.list = value; }
        public void setListSeparator(int value) { this.listSeparatorId = value; }
        public int getNudMorphemeCount() { return this.nudMorphemeCount; }
        public List<ParseTreeNode> getChildren() { return this.children; }
        public boolean isInfix() { return this.isInfix; }
        public boolean isPrefix() { return this.isPrefix; }
        public boolean isExpr() { return this.isExpr; }
        public boolean isNud() { return this.isNud; }
        public boolean isExprNud() { return this.isExprNud; }
        public void add(ParseTreeNode tree) {
            if (this.children == null) {
                this.children = new ArrayList<ParseTreeNode>();
            }
            this.children.add(tree);
        }
        private boolean isCompoundNud() {
            if ( this.children.size() > 0 && this.children.get(0) instanceof ParseTree ) {
                ParseTree child = (ParseTree) this.children.get(0);
                if ( child.isNud() && !child.isPrefix() && !this.isExprNud() && !this.isInfix() ) {
                    return true;
                }
            }
            return false;
        }
        public AstNode toAst() {
            if ( this.list == true ) {
                AstList astList = new AstList();
                if ( this.children.size() == 0 ) {
                    return astList;
                }
                for (int i = 0; i < this.children.size(); i++) {
                    if (this.children.get(i) instanceof Terminal && this.listSeparatorId >= 0 &&
                        ((Terminal) this.children.get(i)).id == this.listSeparatorId) {
                        continue;
                    }
                    astList.add(this.children.get(i).toAst());
                }
                return astList;
            } else if ( this.isExpr ) {
                if ( this.astTransform instanceof AstTransformSubstitution ) {
                    AstTransformSubstitution astSubstitution = (AstTransformSubstitution) astTransform;
                    return this.children.get(astSubstitution.getIndex()).toAst();
                } else if ( this.astTransform instanceof AstTransformNodeCreator ) {
                    AstTransformNodeCreator astNodeCreator = (AstTransformNodeCreator) this.astTransform;
                    LinkedHashMap<String, AstNode> parameters = new LinkedHashMap<String, AstNode>();
                    ParseTreeNode child;
                    for ( final Map.Entry<String, Integer> parameter : astNodeCreator.getParameters().entrySet() ) {
                        String name = parameter.getKey();
                        int index = parameter.getValue().intValue();
                        if ( index == '$' ) {
                            child = this.children.get(0);
                        } else if ( this.isCompoundNud() ) {
                            ParseTree firstChild = (ParseTree) this.children.get(0);
                            if ( index < firstChild.getNudMorphemeCount() ) {
                                child = firstChild.getChildren().get(index);
                            } else {
                                index = index - firstChild.getNudMorphemeCount() + 1;
                                child = this.children.get(index);
                            }
                        } else if ( this.children.size() == 1 && !(this.children.get(0) instanceof ParseTree) && !(this.children.get(0) instanceof List) ) {
                            // TODO: I don't think this should ever be called
                            child = this.children.get(0);
                        } else {
                            child = this.children.get(index);
                        }
                        parameters.put(name, child.toAst());
                    }
                    return new Ast(astNodeCreator.getName(), parameters);
                }
            } else {
                AstTransformSubstitution defaultAction = new AstTransformSubstitution(0);
                AstTransform action = this.astTransform != null ? this.astTransform : defaultAction;
                if (this.children.size() == 0) return null;
                if (action instanceof AstTransformSubstitution) {
                    AstTransformSubstitution astSubstitution = (AstTransformSubstitution) action;
                    return this.children.get(astSubstitution.getIndex()).toAst();
                } else if (action instanceof AstTransformNodeCreator) {
                    AstTransformNodeCreator astNodeCreator = (AstTransformNodeCreator) action;
                    LinkedHashMap<String, AstNode> evaluatedParameters = new LinkedHashMap<String, AstNode>();
                    for ( Map.Entry<String, Integer> baseParameter : astNodeCreator.getParameters().entrySet() ) {
                        String name = baseParameter.getKey();
                        int index2 = baseParameter.getValue().intValue();
                        evaluatedParameters.put(name, this.children.get(index2).toAst());
                    }
                    return new Ast(astNodeCreator.getName(), evaluatedParameters);
                }
            }
            return null;
        }
        public String toString() {
          ArrayList<String> children = new ArrayList<String>();
          for (ParseTreeNode child : this.children) {
            children.add(child.toString());
          }
          return "(" + this.nonterminal.getString() + ": " + join(children, ", ") + ")";
        }
        public String toPrettyString() {
          return toPrettyString(0);
        }
        public String toPrettyString(int indent) {
          if (this.children.size() == 0) {
            return "(" + this.nonterminal.toString() + ": )";
          }
          String spaces = getIndentString(indent);
          ArrayList<String> children = new ArrayList<String>();
          for ( ParseTreeNode node : this.children ) {
            String sub = node.toPrettyString(indent + 2).trim();
            children.add(spaces + "  " +  sub);
          }
          return spaces + "(" + this.nonterminal.toString() + ":\n" + join(children, ",\n") + "\n" + spaces + ")";
        }
    }
    private static class ParserContext {
        public TokenStream tokens;
        public SyntaxErrorFormatter error_formatter;
        public String nonterminal;
        public String rule;
        public ParserContext(TokenStream tokens, SyntaxErrorFormatter error_formatter) {
            this.tokens = tokens;
            this.error_formatter = error_formatter;
        }
    }
    private static class DefaultSyntaxErrorFormatter implements SyntaxErrorFormatter {
        public String unexpectedEof(String method, List<TerminalIdentifier> expected, List<String> nt_rules) {
            return "Error: unexpected end of file";
        }
        public String excessTokens(String method, Terminal terminal) {
            return "Finished parsing without consuming all tokens.";
        }
        public String unexpectedSymbol(String method, Terminal actual, List<TerminalIdentifier> expected, String rule) {
            ArrayList<String> expected_terminals = new ArrayList<String>();
            for ( TerminalIdentifier e : expected ) {
                expected_terminals.add(e.string());
            }
            return String.format(
                "Unexpected symbol (line %d, col %d) when parsing parse_%s.  Expected %s, got %s.",
                actual.getLine(), actual.getColumn(), method, join(expected_terminals, ", "), actual.toPrettyString()
            );
        }
        public String noMoreTokens(String method, TerminalIdentifier expecting, Terminal last) {
            return "No more tokens.  Expecting " + expecting.string();
        }
        public String invalidTerminal(String method, Terminal invalid) {
            return "Invalid symbol ID: "+invalid.getId()+" ("+invalid.getTerminalStr()+")";
        }
        public String missingListItems(String method, int required, int found, Terminal last) {
            return "List for "+method+" requires "+required+" items but only "+found+" were found.";
        }
        public String missingTerminator(String method, TerminalIdentifier terminator, Terminal last) {
            return "List for "+method+" is missing a terminator";
        }
    }
    public interface TerminalMap {
        TerminalIdentifier get(String string);
        TerminalIdentifier get(int id);
        boolean isValid(String string);
        boolean isValid(int id);
    }
    public static class WdlTerminalMap implements TerminalMap {
        private Map<Integer, TerminalIdentifier> id_to_term;
        private Map<String, TerminalIdentifier> str_to_term;
        WdlTerminalMap(WdlTerminalIdentifier[] terminals) {
            id_to_term = new HashMap<Integer, TerminalIdentifier>();
            str_to_term = new HashMap<String, TerminalIdentifier>();
            for( WdlTerminalIdentifier terminal : terminals ) {
                Integer id = new Integer(terminal.id());
                String str = terminal.string();
                id_to_term.put(id, terminal);
                str_to_term.put(str, terminal);
            }
        }
        public TerminalIdentifier get(String string) { return this.str_to_term.get(string); }
        public TerminalIdentifier get(int id) { return this.id_to_term.get(id); }
        public boolean isValid(String string) { return this.str_to_term.containsKey(string); }
        public boolean isValid(int id) { return this.id_to_term.containsKey(id); }
    }
    public interface TerminalIdentifier {
        public int id();
        public String string();
    }
    public enum WdlTerminalIdentifier implements TerminalIdentifier {
        TERMINAL_AS(26, "as"),
        TERMINAL_ASTERISK(25, "asterisk"),
        TERMINAL_BOOLEAN(3, "boolean"),
        TERMINAL_CALL(10, "call"),
        TERMINAL_CMD_ATTR_HINT(30, "cmd_attr_hint"),
        TERMINAL_CMD_PARAM_END(57, "cmd_param_end"),
        TERMINAL_CMD_PARAM_START(5, "cmd_param_start"),
        TERMINAL_CMD_PART(40, "cmd_part"),
        TERMINAL_COLON(52, "colon"),
        TERMINAL_COMMA(23, "comma"),
        TERMINAL_DASH(42, "dash"),
        TERMINAL_DOT(33, "dot"),
        TERMINAL_DOUBLE_AMPERSAND(27, "double_ampersand"),
        TERMINAL_DOUBLE_EQUAL(35, "double_equal"),
        TERMINAL_DOUBLE_PIPE(41, "double_pipe"),
        TERMINAL_E(18, "e"),
        TERMINAL_ELSE(55, "else"),
        TERMINAL_EQUAL(43, "equal"),
        TERMINAL_FLOAT(21, "float"),
        TERMINAL_FQN(44, "fqn"),
        TERMINAL_GT(39, "gt"),
        TERMINAL_GTEQ(9, "gteq"),
        TERMINAL_IDENTIFIER(4, "identifier"),
        TERMINAL_IF(17, "if"),
        TERMINAL_IMPORT(50, "import"),
        TERMINAL_IN(11, "in"),
        TERMINAL_INPUT(24, "input"),
        TERMINAL_INTEGER(16, "integer"),
        TERMINAL_LBRACE(13, "lbrace"),
        TERMINAL_LPAREN(8, "lparen"),
        TERMINAL_LSQUARE(7, "lsquare"),
        TERMINAL_LT(14, "lt"),
        TERMINAL_LTEQ(47, "lteq"),
        TERMINAL_META(6, "meta"),
        TERMINAL_NOT(51, "not"),
        TERMINAL_NOT_EQUAL(36, "not_equal"),
        TERMINAL_NULL(53, "null"),
        TERMINAL_OBJECT(22, "object"),
        TERMINAL_OUTPUT(58, "output"),
        TERMINAL_PARAMETER_META(32, "parameter_meta"),
        TERMINAL_PERCENT(20, "percent"),
        TERMINAL_PLUS(2, "plus"),
        TERMINAL_QMARK(45, "qmark"),
        TERMINAL_RAW_CMD_END(0, "raw_cmd_end"),
        TERMINAL_RAW_CMD_START(56, "raw_cmd_start"),
        TERMINAL_RAW_COMMAND(31, "raw_command"),
        TERMINAL_RBRACE(49, "rbrace"),
        TERMINAL_RPAREN(37, "rparen"),
        TERMINAL_RSQUARE(15, "rsquare"),
        TERMINAL_RUNTIME(48, "runtime"),
        TERMINAL_SCATTER(34, "scatter"),
        TERMINAL_SLASH(19, "slash"),
        TERMINAL_STRING(54, "string"),
        TERMINAL_TASK(12, "task"),
        TERMINAL_THEN(28, "then"),
        TERMINAL_TYPE(38, "type"),
        TERMINAL_TYPE_E(1, "type_e"),
        TERMINAL_WHILE(46, "while"),
        TERMINAL_WORKFLOW(29, "workflow"),
        END_SENTINAL(-3, "END_SENTINAL");
        private final int id;
        private final String string;
        WdlTerminalIdentifier(int id, String string) {
            this.id = id;
            this.string = string;
        }
        public int id() {return id;}
        public String string() {return string;}
    }
    /* table[nonterminal][terminal] = rule */
    private static final int[][] table = {
        { -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 24, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1 },
        { -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 51, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1 },
        { -1, 5, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 4, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 3, -1, -1, -1, -1, -1, -1, -1, -1, 5, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1 },
        { -1, -1, -1, -1, -1, -1, 84, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1 },
        { -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1 },
        { -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1 },
        { -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 85, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1 },
        { -1, -1, -1, -1, -1, -1, 17, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 13, 16, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 15, -1, -1, -1, -1, -1, -1, -1, -1, -1, 14 },
        { -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1 },
        { -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1 },
        { -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1 },
        { -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1 },
        { -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 67, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1 },
        { -1, 2, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 2, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 2, -1, -1, -1, -1, -1, -1, -1, -1, 2, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 2, -1, -1, -1, -1, -1, -1, -1, -1 },
        { -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1 },
        { -1, -1, -1, -1, -1, -1, -1, 47, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1 },
        { -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 71, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1 },
        { -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 86, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1 },
        { -1, 78, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 78, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1 },
        { -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1 },
        { -1, 76, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 76, -1, -1, -1, -1, -1, 77, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1 },
        { -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 28, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1 },
        { -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 12, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1 },
        { -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 82, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1 },
        { -1, 7, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 7, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 6, -1, -1, 7, -1, -1, -1, -1, -1, -1, -1, -1, 7, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 7, -1, -1, -1, -1, -1, -1, -1, -1 },
        { -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 26 },
        { -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1 },
        { -1, 56, -1, -1, -1, -1, 62, -1, -1, -1, 55, -1, -1, -1, -1, -1, -1, 58, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 61, -1, 59, -1, -1, -1, 56, -1, -1, -1, -1, -1, -1, -1, 57, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 60 },
        { -1, -1, -1, -1, 36, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1 },
        { -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1 },
        { -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 9, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1 },
        { -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1 },
        { -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 8, -1, -1, -1, -1, -1, -1, -1, -1 },
        { -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 35, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1 },
        { -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1 },
        { -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 45, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1 },
        { -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 75 },
        { -1, -1, -1, 40, -1, -1, -1, 43, -1, -1, -1, -1, -1, 42, -1, -1, 38, -1, -1, -1, -1, 39, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 41, 37, -1, -1, -1, -1 },
        { -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1 },
        { -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 83, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1 },
        { -1, 49, -1, -1, -1, -1, 49, -1, -1, -1, 49, -1, 49, -1, -1, -1, -1, 49, -1, -1, -1, -1, -1, -1, 49, -1, -1, -1, -1, 49, -1, 49, 49, -1, 49, -1, -1, -1, 49, -1, -1, -1, -1, 48, -1, -1, 49, -1, 49, 49, -1, -1, -1, -1, -1, -1, -1, -1, 49 },
        { -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1 },
        { -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1 },
        { -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 69, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1 },
        { -1, 66, -1, -1, -1, -1, 66, -1, -1, -1, 66, -1, -1, 65, -1, -1, -1, 66, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 66, -1, 66, -1, -1, -1, 66, -1, -1, -1, -1, -1, -1, -1, 66, -1, -1, 66, -1, -1, -1, -1, -1, -1, -1, -1, 66 },
        { -1, -1, -1, -1, -1, -1, 32, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1 },
        { -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 19, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1 },
        { -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 54, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1 },
        { -1, 64, -1, -1, -1, -1, 64, -1, -1, -1, 64, -1, -1, 64, -1, -1, -1, 64, -1, -1, -1, -1, -1, -1, -1, -1, 63, -1, -1, -1, -1, -1, 64, -1, 64, -1, -1, -1, 64, -1, -1, -1, -1, -1, -1, -1, 64, -1, -1, 64, -1, -1, -1, -1, -1, -1, -1, -1, 64 },
        { -1, -1, -1, -1, -1, 23, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1 },
        { -1, 50, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 50, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1 },
        { -1, -1, -1, -1, 88, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1 },
        { -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 87, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1 },
        { -1, 80, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 79, -1, -1, -1, -1, 80, -1, -1, -1, -1, -1, 80, -1, -1, -1, -1, 80, -1, -1, -1, -1, -1, -1, -1, -1, -1 },
        { -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1 },
        { -1, -1, -1, -1, 31, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1 },
        { -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 81, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1 },
        { -1, -1, -1, -1, -1, 21, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 20, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1 },
        { -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1 },
        { -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 30, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1 },
        { -1, -1, 52, 52, 52, -1, -1, 52, 52, -1, -1, -1, -1, 52, -1, -1, 52, 52, 52, -1, -1, 52, 52, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 52, -1, -1, -1, -1, -1, -1, -1, -1, 52, -1, -1, 52, -1, -1, -1, -1 },
        { -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 33, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1 },
        { -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1 },
        { -1, -1, -1, -1, 72, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1 },
        { -1, 27, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 27, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1 },
        { -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1 },
        { -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1 },
        { -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1 },
        { -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 73, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1 },
    };
    static {
        Map<Integer, List<TerminalIdentifier>> map = new HashMap<Integer, List<TerminalIdentifier>>();
        map.put(113, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_IMPORT,
        }));
        map.put(124, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_TASK,
            WdlTerminalIdentifier.TERMINAL_TYPE,
            WdlTerminalIdentifier.TERMINAL_TYPE_E,
            WdlTerminalIdentifier.TERMINAL_WORKFLOW,
        }));
        map.put(67, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_IDENTIFIER,
        }));
        map.put(90, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_BOOLEAN,
            WdlTerminalIdentifier.TERMINAL_FLOAT,
            WdlTerminalIdentifier.TERMINAL_INTEGER,
            WdlTerminalIdentifier.TERMINAL_LBRACE,
            WdlTerminalIdentifier.TERMINAL_LSQUARE,
            WdlTerminalIdentifier.TERMINAL_NULL,
            WdlTerminalIdentifier.TERMINAL_STRING,
        }));
        map.put(99, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_EQUAL,
        }));
        map.put(117, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_CALL,
            WdlTerminalIdentifier.TERMINAL_IF,
            WdlTerminalIdentifier.TERMINAL_META,
            WdlTerminalIdentifier.TERMINAL_OUTPUT,
            WdlTerminalIdentifier.TERMINAL_PARAMETER_META,
            WdlTerminalIdentifier.TERMINAL_SCATTER,
            WdlTerminalIdentifier.TERMINAL_TYPE,
            WdlTerminalIdentifier.TERMINAL_TYPE_E,
            WdlTerminalIdentifier.TERMINAL_WHILE,
        }));
        map.put(107, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_AS,
        }));
        map.put(103, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_LBRACE,
        }));
        map.put(73, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_INPUT,
        }));
        map.put(88, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_IDENTIFIER,
        }));
        map.put(100, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_FQN,
            WdlTerminalIdentifier.TERMINAL_TYPE,
            WdlTerminalIdentifier.TERMINAL_TYPE_E,
        }));
        map.put(112, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_DOT,
        }));
        map.put(83, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_AS,
        }));
        map.put(64, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_TYPE,
            WdlTerminalIdentifier.TERMINAL_TYPE_E,
        }));
        map.put(126, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_BOOLEAN,
            WdlTerminalIdentifier.TERMINAL_DASH,
            WdlTerminalIdentifier.TERMINAL_E,
            WdlTerminalIdentifier.TERMINAL_FLOAT,
            WdlTerminalIdentifier.TERMINAL_IDENTIFIER,
            WdlTerminalIdentifier.TERMINAL_IF,
            WdlTerminalIdentifier.TERMINAL_INTEGER,
            WdlTerminalIdentifier.TERMINAL_LBRACE,
            WdlTerminalIdentifier.TERMINAL_LPAREN,
            WdlTerminalIdentifier.TERMINAL_LSQUARE,
            WdlTerminalIdentifier.TERMINAL_NOT,
            WdlTerminalIdentifier.TERMINAL_OBJECT,
            WdlTerminalIdentifier.TERMINAL_PLUS,
            WdlTerminalIdentifier.TERMINAL_STRING,
        }));
        map.put(68, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_IDENTIFIER,
        }));
        map.put(70, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_BOOLEAN,
            WdlTerminalIdentifier.TERMINAL_DASH,
            WdlTerminalIdentifier.TERMINAL_E,
            WdlTerminalIdentifier.TERMINAL_FLOAT,
            WdlTerminalIdentifier.TERMINAL_IDENTIFIER,
            WdlTerminalIdentifier.TERMINAL_IF,
            WdlTerminalIdentifier.TERMINAL_INTEGER,
            WdlTerminalIdentifier.TERMINAL_LBRACE,
            WdlTerminalIdentifier.TERMINAL_LPAREN,
            WdlTerminalIdentifier.TERMINAL_LSQUARE,
            WdlTerminalIdentifier.TERMINAL_NOT,
            WdlTerminalIdentifier.TERMINAL_OBJECT,
            WdlTerminalIdentifier.TERMINAL_PLUS,
            WdlTerminalIdentifier.TERMINAL_STRING,
        }));
        map.put(85, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_TYPE,
            WdlTerminalIdentifier.TERMINAL_TYPE_E,
        }));
        map.put(78, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_META,
            WdlTerminalIdentifier.TERMINAL_OUTPUT,
            WdlTerminalIdentifier.TERMINAL_PARAMETER_META,
            WdlTerminalIdentifier.TERMINAL_RAW_COMMAND,
            WdlTerminalIdentifier.TERMINAL_RUNTIME,
        }));
        map.put(121, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_CMD_PARAM_START,
            WdlTerminalIdentifier.TERMINAL_CMD_PART,
        }));
        map.put(69, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_CMD_ATTR_HINT,
        }));
        map.put(101, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_TYPE,
            WdlTerminalIdentifier.TERMINAL_TYPE_E,
        }));
        map.put(63, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_IDENTIFIER,
        }));
        map.put(93, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_IDENTIFIER,
        }));
        map.put(127, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_AS,
        }));
        map.put(71, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_CALL,
        }));
        map.put(102, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_LBRACE,
        }));
        map.put(75, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_INPUT,
        }));
        map.put(108, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_CMD_PARAM_START,
        }));
        map.put(59, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_CMD_ATTR_HINT,
        }));
        map.put(105, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_RAW_COMMAND,
        }));
        map.put(116, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_CMD_PARAM_START,
            WdlTerminalIdentifier.TERMINAL_CMD_PART,
        }));
        map.put(109, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_TYPE,
            WdlTerminalIdentifier.TERMINAL_TYPE_E,
        }));
        map.put(72, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_IMPORT,
            WdlTerminalIdentifier.TERMINAL_TASK,
            WdlTerminalIdentifier.TERMINAL_TYPE,
            WdlTerminalIdentifier.TERMINAL_TYPE_E,
            WdlTerminalIdentifier.TERMINAL_WORKFLOW,
        }));
        map.put(97, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_BOOLEAN,
            WdlTerminalIdentifier.TERMINAL_DASH,
            WdlTerminalIdentifier.TERMINAL_E,
            WdlTerminalIdentifier.TERMINAL_FLOAT,
            WdlTerminalIdentifier.TERMINAL_IDENTIFIER,
            WdlTerminalIdentifier.TERMINAL_IF,
            WdlTerminalIdentifier.TERMINAL_INTEGER,
            WdlTerminalIdentifier.TERMINAL_LBRACE,
            WdlTerminalIdentifier.TERMINAL_LPAREN,
            WdlTerminalIdentifier.TERMINAL_LSQUARE,
            WdlTerminalIdentifier.TERMINAL_NOT,
            WdlTerminalIdentifier.TERMINAL_OBJECT,
            WdlTerminalIdentifier.TERMINAL_PLUS,
            WdlTerminalIdentifier.TERMINAL_STRING,
        }));
        map.put(76, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_IF,
        }));
        map.put(91, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_IMPORT,
        }));
        map.put(89, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_AS,
        }));
        map.put(114, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_IDENTIFIER,
        }));
        map.put(118, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_LBRACE,
        }));
        map.put(119, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_BOOLEAN,
            WdlTerminalIdentifier.TERMINAL_DASH,
            WdlTerminalIdentifier.TERMINAL_E,
            WdlTerminalIdentifier.TERMINAL_FLOAT,
            WdlTerminalIdentifier.TERMINAL_IDENTIFIER,
            WdlTerminalIdentifier.TERMINAL_IF,
            WdlTerminalIdentifier.TERMINAL_INTEGER,
            WdlTerminalIdentifier.TERMINAL_LBRACE,
            WdlTerminalIdentifier.TERMINAL_LPAREN,
            WdlTerminalIdentifier.TERMINAL_LSQUARE,
            WdlTerminalIdentifier.TERMINAL_NOT,
            WdlTerminalIdentifier.TERMINAL_OBJECT,
            WdlTerminalIdentifier.TERMINAL_PLUS,
            WdlTerminalIdentifier.TERMINAL_STRING,
        }));
        map.put(122, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_IDENTIFIER,
        }));
        map.put(104, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_META,
        }));
        map.put(74, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_LSQUARE,
        }));
        map.put(87, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_IDENTIFIER,
        }));
        map.put(92, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_LBRACE,
        }));
        map.put(94, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_LBRACE,
        }));
        map.put(96, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_BOOLEAN,
            WdlTerminalIdentifier.TERMINAL_FLOAT,
            WdlTerminalIdentifier.TERMINAL_INTEGER,
            WdlTerminalIdentifier.TERMINAL_LBRACE,
            WdlTerminalIdentifier.TERMINAL_LSQUARE,
            WdlTerminalIdentifier.TERMINAL_NULL,
            WdlTerminalIdentifier.TERMINAL_STRING,
        }));
        map.put(110, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_IDENTIFIER,
        }));
        map.put(123, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_TYPE,
            WdlTerminalIdentifier.TERMINAL_TYPE_E,
        }));
        map.put(84, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_OUTPUT,
        }));
        map.put(120, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_PARAMETER_META,
        }));
        map.put(80, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_RUNTIME,
        }));
        map.put(111, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_SCATTER,
        }));
        map.put(66, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_META,
            WdlTerminalIdentifier.TERMINAL_OUTPUT,
            WdlTerminalIdentifier.TERMINAL_PARAMETER_META,
            WdlTerminalIdentifier.TERMINAL_RAW_COMMAND,
            WdlTerminalIdentifier.TERMINAL_RUNTIME,
        }));
        map.put(60, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_EQUAL,
        }));
        map.put(81, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_TASK,
        }));
        map.put(125, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_TYPE,
            WdlTerminalIdentifier.TERMINAL_TYPE_E,
        }));
        map.put(86, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_CALL,
            WdlTerminalIdentifier.TERMINAL_IF,
            WdlTerminalIdentifier.TERMINAL_META,
            WdlTerminalIdentifier.TERMINAL_OUTPUT,
            WdlTerminalIdentifier.TERMINAL_PARAMETER_META,
            WdlTerminalIdentifier.TERMINAL_SCATTER,
            WdlTerminalIdentifier.TERMINAL_TYPE,
            WdlTerminalIdentifier.TERMINAL_TYPE_E,
            WdlTerminalIdentifier.TERMINAL_WHILE,
        }));
        map.put(62, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_META,
        }));
        map.put(79, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_FQN,
            WdlTerminalIdentifier.TERMINAL_TYPE,
            WdlTerminalIdentifier.TERMINAL_TYPE_E,
        }));
        map.put(77, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_TYPE,
            WdlTerminalIdentifier.TERMINAL_TYPE_E,
        }));
        map.put(82, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_DOT,
        }));
        map.put(115, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_FQN,
        }));
        map.put(95, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_OUTPUT,
        }));
        map.put(98, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_PARAMETER_META,
        }));
        map.put(65, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_WHILE,
        }));
        map.put(106, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_WORKFLOW,
        }));
        map.put(61, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_TASK,
            WdlTerminalIdentifier.TERMINAL_TYPE,
            WdlTerminalIdentifier.TERMINAL_TYPE_E,
            WdlTerminalIdentifier.TERMINAL_WORKFLOW,
        }));
        nonterminal_first = Collections.unmodifiableMap(map);
    }
    static {
        Map<Integer, List<TerminalIdentifier>> map = new HashMap<Integer, List<TerminalIdentifier>>();
        map.put(113, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_TASK,
            WdlTerminalIdentifier.TERMINAL_TYPE,
            WdlTerminalIdentifier.TERMINAL_TYPE_E,
            WdlTerminalIdentifier.TERMINAL_WORKFLOW,
        }));
        map.put(124, Arrays.asList(new TerminalIdentifier[] {
        }));
        map.put(67, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_RBRACE,
        }));
        map.put(90, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_RSQUARE,
        }));
        map.put(99, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_CALL,
            WdlTerminalIdentifier.TERMINAL_IF,
            WdlTerminalIdentifier.TERMINAL_INPUT,
            WdlTerminalIdentifier.TERMINAL_META,
            WdlTerminalIdentifier.TERMINAL_OUTPUT,
            WdlTerminalIdentifier.TERMINAL_PARAMETER_META,
            WdlTerminalIdentifier.TERMINAL_RAW_COMMAND,
            WdlTerminalIdentifier.TERMINAL_RBRACE,
            WdlTerminalIdentifier.TERMINAL_RUNTIME,
            WdlTerminalIdentifier.TERMINAL_SCATTER,
            WdlTerminalIdentifier.TERMINAL_TASK,
            WdlTerminalIdentifier.TERMINAL_TYPE,
            WdlTerminalIdentifier.TERMINAL_TYPE_E,
            WdlTerminalIdentifier.TERMINAL_WHILE,
            WdlTerminalIdentifier.TERMINAL_WORKFLOW,
        }));
        map.put(117, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_RBRACE,
        }));
        map.put(107, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_CALL,
            WdlTerminalIdentifier.TERMINAL_IF,
            WdlTerminalIdentifier.TERMINAL_LBRACE,
            WdlTerminalIdentifier.TERMINAL_META,
            WdlTerminalIdentifier.TERMINAL_OUTPUT,
            WdlTerminalIdentifier.TERMINAL_PARAMETER_META,
            WdlTerminalIdentifier.TERMINAL_RBRACE,
            WdlTerminalIdentifier.TERMINAL_SCATTER,
            WdlTerminalIdentifier.TERMINAL_TYPE,
            WdlTerminalIdentifier.TERMINAL_TYPE_E,
            WdlTerminalIdentifier.TERMINAL_WHILE,
        }));
        map.put(103, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_CALL,
            WdlTerminalIdentifier.TERMINAL_IF,
            WdlTerminalIdentifier.TERMINAL_META,
            WdlTerminalIdentifier.TERMINAL_OUTPUT,
            WdlTerminalIdentifier.TERMINAL_PARAMETER_META,
            WdlTerminalIdentifier.TERMINAL_RBRACE,
            WdlTerminalIdentifier.TERMINAL_SCATTER,
            WdlTerminalIdentifier.TERMINAL_TYPE,
            WdlTerminalIdentifier.TERMINAL_TYPE_E,
            WdlTerminalIdentifier.TERMINAL_WHILE,
        }));
        map.put(73, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_RBRACE,
        }));
        map.put(88, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_INPUT,
            WdlTerminalIdentifier.TERMINAL_RBRACE,
        }));
        map.put(100, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_RBRACE,
        }));
        map.put(112, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_FQN,
            WdlTerminalIdentifier.TERMINAL_RBRACE,
            WdlTerminalIdentifier.TERMINAL_TYPE,
            WdlTerminalIdentifier.TERMINAL_TYPE_E,
        }));
        map.put(83, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_IMPORT,
            WdlTerminalIdentifier.TERMINAL_TASK,
            WdlTerminalIdentifier.TERMINAL_TYPE,
            WdlTerminalIdentifier.TERMINAL_TYPE_E,
            WdlTerminalIdentifier.TERMINAL_WORKFLOW,
        }));
        map.put(64, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_RSQUARE,
        }));
        map.put(126, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_RPAREN,
            WdlTerminalIdentifier.TERMINAL_RSQUARE,
        }));
        map.put(68, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_RBRACE,
        }));
        map.put(70, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_RBRACE,
        }));
        map.put(85, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_INPUT,
            WdlTerminalIdentifier.TERMINAL_META,
            WdlTerminalIdentifier.TERMINAL_OUTPUT,
            WdlTerminalIdentifier.TERMINAL_PARAMETER_META,
            WdlTerminalIdentifier.TERMINAL_RAW_COMMAND,
            WdlTerminalIdentifier.TERMINAL_RUNTIME,
        }));
        map.put(78, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_RBRACE,
        }));
        map.put(121, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_RAW_CMD_END,
        }));
        map.put(69, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_BOOLEAN,
            WdlTerminalIdentifier.TERMINAL_DASH,
            WdlTerminalIdentifier.TERMINAL_E,
            WdlTerminalIdentifier.TERMINAL_FLOAT,
            WdlTerminalIdentifier.TERMINAL_IDENTIFIER,
            WdlTerminalIdentifier.TERMINAL_IF,
            WdlTerminalIdentifier.TERMINAL_INTEGER,
            WdlTerminalIdentifier.TERMINAL_LBRACE,
            WdlTerminalIdentifier.TERMINAL_LPAREN,
            WdlTerminalIdentifier.TERMINAL_LSQUARE,
            WdlTerminalIdentifier.TERMINAL_NOT,
            WdlTerminalIdentifier.TERMINAL_OBJECT,
            WdlTerminalIdentifier.TERMINAL_PLUS,
            WdlTerminalIdentifier.TERMINAL_STRING,
        }));
        map.put(101, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_RBRACE,
        }));
        map.put(63, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_RBRACE,
        }));
        map.put(93, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_RBRACE,
        }));
        map.put(127, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_CALL,
            WdlTerminalIdentifier.TERMINAL_IF,
            WdlTerminalIdentifier.TERMINAL_LBRACE,
            WdlTerminalIdentifier.TERMINAL_META,
            WdlTerminalIdentifier.TERMINAL_OUTPUT,
            WdlTerminalIdentifier.TERMINAL_PARAMETER_META,
            WdlTerminalIdentifier.TERMINAL_RBRACE,
            WdlTerminalIdentifier.TERMINAL_SCATTER,
            WdlTerminalIdentifier.TERMINAL_TYPE,
            WdlTerminalIdentifier.TERMINAL_TYPE_E,
            WdlTerminalIdentifier.TERMINAL_WHILE,
        }));
        map.put(71, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_CALL,
            WdlTerminalIdentifier.TERMINAL_IF,
            WdlTerminalIdentifier.TERMINAL_META,
            WdlTerminalIdentifier.TERMINAL_OUTPUT,
            WdlTerminalIdentifier.TERMINAL_PARAMETER_META,
            WdlTerminalIdentifier.TERMINAL_RBRACE,
            WdlTerminalIdentifier.TERMINAL_SCATTER,
            WdlTerminalIdentifier.TERMINAL_TYPE,
            WdlTerminalIdentifier.TERMINAL_TYPE_E,
            WdlTerminalIdentifier.TERMINAL_WHILE,
        }));
        map.put(102, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_CALL,
            WdlTerminalIdentifier.TERMINAL_IF,
            WdlTerminalIdentifier.TERMINAL_META,
            WdlTerminalIdentifier.TERMINAL_OUTPUT,
            WdlTerminalIdentifier.TERMINAL_PARAMETER_META,
            WdlTerminalIdentifier.TERMINAL_RBRACE,
            WdlTerminalIdentifier.TERMINAL_SCATTER,
            WdlTerminalIdentifier.TERMINAL_TYPE,
            WdlTerminalIdentifier.TERMINAL_TYPE_E,
            WdlTerminalIdentifier.TERMINAL_WHILE,
        }));
        map.put(75, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_INPUT,
            WdlTerminalIdentifier.TERMINAL_RBRACE,
        }));
        map.put(108, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_CMD_PARAM_START,
            WdlTerminalIdentifier.TERMINAL_CMD_PART,
            WdlTerminalIdentifier.TERMINAL_RAW_CMD_END,
        }));
        map.put(59, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_BOOLEAN,
            WdlTerminalIdentifier.TERMINAL_CMD_ATTR_HINT,
            WdlTerminalIdentifier.TERMINAL_DASH,
            WdlTerminalIdentifier.TERMINAL_E,
            WdlTerminalIdentifier.TERMINAL_FLOAT,
            WdlTerminalIdentifier.TERMINAL_IDENTIFIER,
            WdlTerminalIdentifier.TERMINAL_IF,
            WdlTerminalIdentifier.TERMINAL_INTEGER,
            WdlTerminalIdentifier.TERMINAL_LBRACE,
            WdlTerminalIdentifier.TERMINAL_LPAREN,
            WdlTerminalIdentifier.TERMINAL_LSQUARE,
            WdlTerminalIdentifier.TERMINAL_NOT,
            WdlTerminalIdentifier.TERMINAL_OBJECT,
            WdlTerminalIdentifier.TERMINAL_PLUS,
            WdlTerminalIdentifier.TERMINAL_STRING,
        }));
        map.put(105, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_META,
            WdlTerminalIdentifier.TERMINAL_OUTPUT,
            WdlTerminalIdentifier.TERMINAL_PARAMETER_META,
            WdlTerminalIdentifier.TERMINAL_RAW_COMMAND,
            WdlTerminalIdentifier.TERMINAL_RBRACE,
            WdlTerminalIdentifier.TERMINAL_RUNTIME,
        }));
        map.put(116, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_CMD_PARAM_START,
            WdlTerminalIdentifier.TERMINAL_CMD_PART,
            WdlTerminalIdentifier.TERMINAL_RAW_CMD_END,
        }));
        map.put(109, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_CALL,
            WdlTerminalIdentifier.TERMINAL_IF,
            WdlTerminalIdentifier.TERMINAL_INPUT,
            WdlTerminalIdentifier.TERMINAL_META,
            WdlTerminalIdentifier.TERMINAL_OUTPUT,
            WdlTerminalIdentifier.TERMINAL_PARAMETER_META,
            WdlTerminalIdentifier.TERMINAL_RAW_COMMAND,
            WdlTerminalIdentifier.TERMINAL_RBRACE,
            WdlTerminalIdentifier.TERMINAL_RUNTIME,
            WdlTerminalIdentifier.TERMINAL_SCATTER,
            WdlTerminalIdentifier.TERMINAL_TASK,
            WdlTerminalIdentifier.TERMINAL_TYPE,
            WdlTerminalIdentifier.TERMINAL_TYPE_E,
            WdlTerminalIdentifier.TERMINAL_WHILE,
            WdlTerminalIdentifier.TERMINAL_WORKFLOW,
        }));
        map.put(72, Arrays.asList(new TerminalIdentifier[] {
        }));
        map.put(97, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_ASTERISK,
            WdlTerminalIdentifier.TERMINAL_BOOLEAN,
            WdlTerminalIdentifier.TERMINAL_CALL,
            WdlTerminalIdentifier.TERMINAL_CMD_ATTR_HINT,
            WdlTerminalIdentifier.TERMINAL_CMD_PARAM_END,
            WdlTerminalIdentifier.TERMINAL_COLON,
            WdlTerminalIdentifier.TERMINAL_COMMA,
            WdlTerminalIdentifier.TERMINAL_DASH,
            WdlTerminalIdentifier.TERMINAL_DOUBLE_AMPERSAND,
            WdlTerminalIdentifier.TERMINAL_DOUBLE_EQUAL,
            WdlTerminalIdentifier.TERMINAL_DOUBLE_PIPE,
            WdlTerminalIdentifier.TERMINAL_E,
            WdlTerminalIdentifier.TERMINAL_ELSE,
            WdlTerminalIdentifier.TERMINAL_FLOAT,
            WdlTerminalIdentifier.TERMINAL_FQN,
            WdlTerminalIdentifier.TERMINAL_GT,
            WdlTerminalIdentifier.TERMINAL_GTEQ,
            WdlTerminalIdentifier.TERMINAL_IDENTIFIER,
            WdlTerminalIdentifier.TERMINAL_IF,
            WdlTerminalIdentifier.TERMINAL_INPUT,
            WdlTerminalIdentifier.TERMINAL_INTEGER,
            WdlTerminalIdentifier.TERMINAL_LBRACE,
            WdlTerminalIdentifier.TERMINAL_LPAREN,
            WdlTerminalIdentifier.TERMINAL_LSQUARE,
            WdlTerminalIdentifier.TERMINAL_LT,
            WdlTerminalIdentifier.TERMINAL_LTEQ,
            WdlTerminalIdentifier.TERMINAL_META,
            WdlTerminalIdentifier.TERMINAL_NOT,
            WdlTerminalIdentifier.TERMINAL_NOT_EQUAL,
            WdlTerminalIdentifier.TERMINAL_OBJECT,
            WdlTerminalIdentifier.TERMINAL_OUTPUT,
            WdlTerminalIdentifier.TERMINAL_PARAMETER_META,
            WdlTerminalIdentifier.TERMINAL_PERCENT,
            WdlTerminalIdentifier.TERMINAL_PLUS,
            WdlTerminalIdentifier.TERMINAL_RAW_COMMAND,
            WdlTerminalIdentifier.TERMINAL_RBRACE,
            WdlTerminalIdentifier.TERMINAL_RPAREN,
            WdlTerminalIdentifier.TERMINAL_RSQUARE,
            WdlTerminalIdentifier.TERMINAL_RUNTIME,
            WdlTerminalIdentifier.TERMINAL_SCATTER,
            WdlTerminalIdentifier.TERMINAL_SLASH,
            WdlTerminalIdentifier.TERMINAL_STRING,
            WdlTerminalIdentifier.TERMINAL_TASK,
            WdlTerminalIdentifier.TERMINAL_THEN,
            WdlTerminalIdentifier.TERMINAL_TYPE,
            WdlTerminalIdentifier.TERMINAL_TYPE_E,
            WdlTerminalIdentifier.TERMINAL_WHILE,
            WdlTerminalIdentifier.TERMINAL_WORKFLOW,
        }));
        map.put(76, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_CALL,
            WdlTerminalIdentifier.TERMINAL_IF,
            WdlTerminalIdentifier.TERMINAL_META,
            WdlTerminalIdentifier.TERMINAL_OUTPUT,
            WdlTerminalIdentifier.TERMINAL_PARAMETER_META,
            WdlTerminalIdentifier.TERMINAL_RBRACE,
            WdlTerminalIdentifier.TERMINAL_SCATTER,
            WdlTerminalIdentifier.TERMINAL_TYPE,
            WdlTerminalIdentifier.TERMINAL_TYPE_E,
            WdlTerminalIdentifier.TERMINAL_WHILE,
        }));
        map.put(91, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_IMPORT,
            WdlTerminalIdentifier.TERMINAL_TASK,
            WdlTerminalIdentifier.TERMINAL_TYPE,
            WdlTerminalIdentifier.TERMINAL_TYPE_E,
            WdlTerminalIdentifier.TERMINAL_WORKFLOW,
        }));
        map.put(89, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_IMPORT,
            WdlTerminalIdentifier.TERMINAL_TASK,
            WdlTerminalIdentifier.TERMINAL_TYPE,
            WdlTerminalIdentifier.TERMINAL_TYPE_E,
            WdlTerminalIdentifier.TERMINAL_WORKFLOW,
        }));
        map.put(114, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_IDENTIFIER,
            WdlTerminalIdentifier.TERMINAL_RBRACE,
        }));
        map.put(118, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_CALL,
            WdlTerminalIdentifier.TERMINAL_IF,
            WdlTerminalIdentifier.TERMINAL_META,
            WdlTerminalIdentifier.TERMINAL_OUTPUT,
            WdlTerminalIdentifier.TERMINAL_PARAMETER_META,
            WdlTerminalIdentifier.TERMINAL_RAW_COMMAND,
            WdlTerminalIdentifier.TERMINAL_RBRACE,
            WdlTerminalIdentifier.TERMINAL_RUNTIME,
            WdlTerminalIdentifier.TERMINAL_SCATTER,
            WdlTerminalIdentifier.TERMINAL_TYPE,
            WdlTerminalIdentifier.TERMINAL_TYPE_E,
            WdlTerminalIdentifier.TERMINAL_WHILE,
        }));
        map.put(119, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_COMMA,
            WdlTerminalIdentifier.TERMINAL_RBRACE,
        }));
        map.put(122, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_COMMA,
            WdlTerminalIdentifier.TERMINAL_INPUT,
            WdlTerminalIdentifier.TERMINAL_RBRACE,
        }));
        map.put(104, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_META,
            WdlTerminalIdentifier.TERMINAL_OUTPUT,
            WdlTerminalIdentifier.TERMINAL_PARAMETER_META,
            WdlTerminalIdentifier.TERMINAL_RAW_COMMAND,
            WdlTerminalIdentifier.TERMINAL_RBRACE,
            WdlTerminalIdentifier.TERMINAL_RUNTIME,
        }));
        map.put(74, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_COMMA,
            WdlTerminalIdentifier.TERMINAL_IDENTIFIER,
            WdlTerminalIdentifier.TERMINAL_RBRACE,
            WdlTerminalIdentifier.TERMINAL_RSQUARE,
        }));
        map.put(87, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_COMMA,
            WdlTerminalIdentifier.TERMINAL_IDENTIFIER,
            WdlTerminalIdentifier.TERMINAL_RBRACE,
        }));
        map.put(92, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_META,
            WdlTerminalIdentifier.TERMINAL_OUTPUT,
            WdlTerminalIdentifier.TERMINAL_PARAMETER_META,
            WdlTerminalIdentifier.TERMINAL_RAW_COMMAND,
            WdlTerminalIdentifier.TERMINAL_RBRACE,
            WdlTerminalIdentifier.TERMINAL_RUNTIME,
        }));
        map.put(94, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_COMMA,
            WdlTerminalIdentifier.TERMINAL_IDENTIFIER,
            WdlTerminalIdentifier.TERMINAL_RBRACE,
            WdlTerminalIdentifier.TERMINAL_RSQUARE,
        }));
        map.put(96, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_COMMA,
            WdlTerminalIdentifier.TERMINAL_IDENTIFIER,
            WdlTerminalIdentifier.TERMINAL_RBRACE,
            WdlTerminalIdentifier.TERMINAL_RSQUARE,
        }));
        map.put(110, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_COMMA,
            WdlTerminalIdentifier.TERMINAL_RBRACE,
        }));
        map.put(123, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_RBRACE,
            WdlTerminalIdentifier.TERMINAL_TYPE,
            WdlTerminalIdentifier.TERMINAL_TYPE_E,
        }));
        map.put(84, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_META,
            WdlTerminalIdentifier.TERMINAL_OUTPUT,
            WdlTerminalIdentifier.TERMINAL_PARAMETER_META,
            WdlTerminalIdentifier.TERMINAL_RAW_COMMAND,
            WdlTerminalIdentifier.TERMINAL_RBRACE,
            WdlTerminalIdentifier.TERMINAL_RUNTIME,
        }));
        map.put(120, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_META,
            WdlTerminalIdentifier.TERMINAL_OUTPUT,
            WdlTerminalIdentifier.TERMINAL_PARAMETER_META,
            WdlTerminalIdentifier.TERMINAL_RAW_COMMAND,
            WdlTerminalIdentifier.TERMINAL_RBRACE,
            WdlTerminalIdentifier.TERMINAL_RUNTIME,
        }));
        map.put(80, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_META,
            WdlTerminalIdentifier.TERMINAL_OUTPUT,
            WdlTerminalIdentifier.TERMINAL_PARAMETER_META,
            WdlTerminalIdentifier.TERMINAL_RAW_COMMAND,
            WdlTerminalIdentifier.TERMINAL_RBRACE,
            WdlTerminalIdentifier.TERMINAL_RUNTIME,
        }));
        map.put(111, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_CALL,
            WdlTerminalIdentifier.TERMINAL_IF,
            WdlTerminalIdentifier.TERMINAL_META,
            WdlTerminalIdentifier.TERMINAL_OUTPUT,
            WdlTerminalIdentifier.TERMINAL_PARAMETER_META,
            WdlTerminalIdentifier.TERMINAL_RBRACE,
            WdlTerminalIdentifier.TERMINAL_SCATTER,
            WdlTerminalIdentifier.TERMINAL_TYPE,
            WdlTerminalIdentifier.TERMINAL_TYPE_E,
            WdlTerminalIdentifier.TERMINAL_WHILE,
        }));
        map.put(66, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_META,
            WdlTerminalIdentifier.TERMINAL_OUTPUT,
            WdlTerminalIdentifier.TERMINAL_PARAMETER_META,
            WdlTerminalIdentifier.TERMINAL_RAW_COMMAND,
            WdlTerminalIdentifier.TERMINAL_RBRACE,
            WdlTerminalIdentifier.TERMINAL_RUNTIME,
        }));
        map.put(60, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_CALL,
            WdlTerminalIdentifier.TERMINAL_IF,
            WdlTerminalIdentifier.TERMINAL_INPUT,
            WdlTerminalIdentifier.TERMINAL_META,
            WdlTerminalIdentifier.TERMINAL_OUTPUT,
            WdlTerminalIdentifier.TERMINAL_PARAMETER_META,
            WdlTerminalIdentifier.TERMINAL_RAW_COMMAND,
            WdlTerminalIdentifier.TERMINAL_RBRACE,
            WdlTerminalIdentifier.TERMINAL_RUNTIME,
            WdlTerminalIdentifier.TERMINAL_SCATTER,
            WdlTerminalIdentifier.TERMINAL_TASK,
            WdlTerminalIdentifier.TERMINAL_TYPE,
            WdlTerminalIdentifier.TERMINAL_TYPE_E,
            WdlTerminalIdentifier.TERMINAL_WHILE,
            WdlTerminalIdentifier.TERMINAL_WORKFLOW,
        }));
        map.put(81, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_TASK,
            WdlTerminalIdentifier.TERMINAL_TYPE,
            WdlTerminalIdentifier.TERMINAL_TYPE_E,
            WdlTerminalIdentifier.TERMINAL_WORKFLOW,
        }));
        map.put(125, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_COMMA,
            WdlTerminalIdentifier.TERMINAL_IDENTIFIER,
            WdlTerminalIdentifier.TERMINAL_RSQUARE,
        }));
        map.put(86, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_CALL,
            WdlTerminalIdentifier.TERMINAL_IF,
            WdlTerminalIdentifier.TERMINAL_META,
            WdlTerminalIdentifier.TERMINAL_OUTPUT,
            WdlTerminalIdentifier.TERMINAL_PARAMETER_META,
            WdlTerminalIdentifier.TERMINAL_RBRACE,
            WdlTerminalIdentifier.TERMINAL_SCATTER,
            WdlTerminalIdentifier.TERMINAL_TYPE,
            WdlTerminalIdentifier.TERMINAL_TYPE_E,
            WdlTerminalIdentifier.TERMINAL_WHILE,
        }));
        map.put(62, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_CALL,
            WdlTerminalIdentifier.TERMINAL_IF,
            WdlTerminalIdentifier.TERMINAL_META,
            WdlTerminalIdentifier.TERMINAL_OUTPUT,
            WdlTerminalIdentifier.TERMINAL_PARAMETER_META,
            WdlTerminalIdentifier.TERMINAL_RBRACE,
            WdlTerminalIdentifier.TERMINAL_SCATTER,
            WdlTerminalIdentifier.TERMINAL_TYPE,
            WdlTerminalIdentifier.TERMINAL_TYPE_E,
            WdlTerminalIdentifier.TERMINAL_WHILE,
        }));
        map.put(79, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_FQN,
            WdlTerminalIdentifier.TERMINAL_RBRACE,
            WdlTerminalIdentifier.TERMINAL_TYPE,
            WdlTerminalIdentifier.TERMINAL_TYPE_E,
        }));
        map.put(77, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_FQN,
            WdlTerminalIdentifier.TERMINAL_RBRACE,
            WdlTerminalIdentifier.TERMINAL_TYPE,
            WdlTerminalIdentifier.TERMINAL_TYPE_E,
        }));
        map.put(82, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_FQN,
            WdlTerminalIdentifier.TERMINAL_RBRACE,
            WdlTerminalIdentifier.TERMINAL_TYPE,
            WdlTerminalIdentifier.TERMINAL_TYPE_E,
        }));
        map.put(115, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_FQN,
            WdlTerminalIdentifier.TERMINAL_RBRACE,
            WdlTerminalIdentifier.TERMINAL_TYPE,
            WdlTerminalIdentifier.TERMINAL_TYPE_E,
        }));
        map.put(95, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_CALL,
            WdlTerminalIdentifier.TERMINAL_IF,
            WdlTerminalIdentifier.TERMINAL_META,
            WdlTerminalIdentifier.TERMINAL_OUTPUT,
            WdlTerminalIdentifier.TERMINAL_PARAMETER_META,
            WdlTerminalIdentifier.TERMINAL_RBRACE,
            WdlTerminalIdentifier.TERMINAL_SCATTER,
            WdlTerminalIdentifier.TERMINAL_TYPE,
            WdlTerminalIdentifier.TERMINAL_TYPE_E,
            WdlTerminalIdentifier.TERMINAL_WHILE,
        }));
        map.put(98, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_CALL,
            WdlTerminalIdentifier.TERMINAL_IF,
            WdlTerminalIdentifier.TERMINAL_META,
            WdlTerminalIdentifier.TERMINAL_OUTPUT,
            WdlTerminalIdentifier.TERMINAL_PARAMETER_META,
            WdlTerminalIdentifier.TERMINAL_RBRACE,
            WdlTerminalIdentifier.TERMINAL_SCATTER,
            WdlTerminalIdentifier.TERMINAL_TYPE,
            WdlTerminalIdentifier.TERMINAL_TYPE_E,
            WdlTerminalIdentifier.TERMINAL_WHILE,
        }));
        map.put(65, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_CALL,
            WdlTerminalIdentifier.TERMINAL_IF,
            WdlTerminalIdentifier.TERMINAL_META,
            WdlTerminalIdentifier.TERMINAL_OUTPUT,
            WdlTerminalIdentifier.TERMINAL_PARAMETER_META,
            WdlTerminalIdentifier.TERMINAL_RBRACE,
            WdlTerminalIdentifier.TERMINAL_SCATTER,
            WdlTerminalIdentifier.TERMINAL_TYPE,
            WdlTerminalIdentifier.TERMINAL_TYPE_E,
            WdlTerminalIdentifier.TERMINAL_WHILE,
        }));
        map.put(106, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_TASK,
            WdlTerminalIdentifier.TERMINAL_TYPE,
            WdlTerminalIdentifier.TERMINAL_TYPE_E,
            WdlTerminalIdentifier.TERMINAL_WORKFLOW,
        }));
        map.put(61, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_TASK,
            WdlTerminalIdentifier.TERMINAL_TYPE,
            WdlTerminalIdentifier.TERMINAL_TYPE_E,
            WdlTerminalIdentifier.TERMINAL_WORKFLOW,
        }));
        nonterminal_follow = Collections.unmodifiableMap(map);
    }
    static {
        Map<Integer, List<TerminalIdentifier>> map = new HashMap<Integer, List<TerminalIdentifier>>();
        map.put(0, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_IMPORT,
        }));
        map.put(1, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_TASK,
            WdlTerminalIdentifier.TERMINAL_TYPE,
            WdlTerminalIdentifier.TERMINAL_TYPE_E,
            WdlTerminalIdentifier.TERMINAL_WORKFLOW,
        }));
        map.put(44, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_IDENTIFIER,
        }));
        map.put(46, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_BOOLEAN,
            WdlTerminalIdentifier.TERMINAL_FLOAT,
            WdlTerminalIdentifier.TERMINAL_INTEGER,
            WdlTerminalIdentifier.TERMINAL_LBRACE,
            WdlTerminalIdentifier.TERMINAL_LSQUARE,
            WdlTerminalIdentifier.TERMINAL_NULL,
            WdlTerminalIdentifier.TERMINAL_STRING,
        }));
        map.put(48, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_EQUAL,
        }));
        map.put(49, Arrays.asList(new TerminalIdentifier[] {
        }));
        map.put(53, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_CALL,
            WdlTerminalIdentifier.TERMINAL_IF,
            WdlTerminalIdentifier.TERMINAL_META,
            WdlTerminalIdentifier.TERMINAL_OUTPUT,
            WdlTerminalIdentifier.TERMINAL_PARAMETER_META,
            WdlTerminalIdentifier.TERMINAL_SCATTER,
            WdlTerminalIdentifier.TERMINAL_TYPE,
            WdlTerminalIdentifier.TERMINAL_TYPE_E,
            WdlTerminalIdentifier.TERMINAL_WHILE,
        }));
        map.put(63, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_AS,
        }));
        map.put(64, Arrays.asList(new TerminalIdentifier[] {
        }));
        map.put(65, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_LBRACE,
        }));
        map.put(66, Arrays.asList(new TerminalIdentifier[] {
        }));
        map.put(68, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_INPUT,
        }));
        map.put(70, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_IDENTIFIER,
        }));
        map.put(74, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_FQN,
            WdlTerminalIdentifier.TERMINAL_TYPE,
            WdlTerminalIdentifier.TERMINAL_TYPE_E,
        }));
        map.put(79, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_DOT,
        }));
        map.put(80, Arrays.asList(new TerminalIdentifier[] {
        }));
        map.put(6, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_AS,
        }));
        map.put(7, Arrays.asList(new TerminalIdentifier[] {
        }));
        map.put(89, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_TYPE,
            WdlTerminalIdentifier.TERMINAL_TYPE_E,
        }));
        map.put(110, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_BOOLEAN,
            WdlTerminalIdentifier.TERMINAL_DASH,
            WdlTerminalIdentifier.TERMINAL_E,
            WdlTerminalIdentifier.TERMINAL_FLOAT,
            WdlTerminalIdentifier.TERMINAL_IDENTIFIER,
            WdlTerminalIdentifier.TERMINAL_IF,
            WdlTerminalIdentifier.TERMINAL_INTEGER,
            WdlTerminalIdentifier.TERMINAL_LBRACE,
            WdlTerminalIdentifier.TERMINAL_LPAREN,
            WdlTerminalIdentifier.TERMINAL_LSQUARE,
            WdlTerminalIdentifier.TERMINAL_NOT,
            WdlTerminalIdentifier.TERMINAL_OBJECT,
            WdlTerminalIdentifier.TERMINAL_PLUS,
            WdlTerminalIdentifier.TERMINAL_STRING,
        }));
        map.put(114, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_IDENTIFIER,
        }));
        map.put(117, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_BOOLEAN,
            WdlTerminalIdentifier.TERMINAL_DASH,
            WdlTerminalIdentifier.TERMINAL_E,
            WdlTerminalIdentifier.TERMINAL_FLOAT,
            WdlTerminalIdentifier.TERMINAL_IDENTIFIER,
            WdlTerminalIdentifier.TERMINAL_IF,
            WdlTerminalIdentifier.TERMINAL_INTEGER,
            WdlTerminalIdentifier.TERMINAL_LBRACE,
            WdlTerminalIdentifier.TERMINAL_LPAREN,
            WdlTerminalIdentifier.TERMINAL_LSQUARE,
            WdlTerminalIdentifier.TERMINAL_NOT,
            WdlTerminalIdentifier.TERMINAL_OBJECT,
            WdlTerminalIdentifier.TERMINAL_PLUS,
            WdlTerminalIdentifier.TERMINAL_STRING,
        }));
        map.put(10, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_TYPE,
            WdlTerminalIdentifier.TERMINAL_TYPE_E,
        }));
        map.put(11, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_META,
            WdlTerminalIdentifier.TERMINAL_OUTPUT,
            WdlTerminalIdentifier.TERMINAL_PARAMETER_META,
            WdlTerminalIdentifier.TERMINAL_RAW_COMMAND,
            WdlTerminalIdentifier.TERMINAL_RUNTIME,
        }));
        map.put(18, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_CMD_PARAM_START,
            WdlTerminalIdentifier.TERMINAL_CMD_PART,
        }));
        map.put(22, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_CMD_ATTR_HINT,
        }));
        map.put(25, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_TYPE,
            WdlTerminalIdentifier.TERMINAL_TYPE_E,
        }));
        map.put(29, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_IDENTIFIER,
        }));
        map.put(34, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_IDENTIFIER,
        }));
        map.put(73, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_AS,
        }));
        map.put(67, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_CALL,
        }));
        map.put(69, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_LBRACE,
        }));
        map.put(71, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_INPUT,
        }));
        map.put(23, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_CMD_PARAM_START,
        }));
        map.put(24, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_CMD_ATTR_HINT,
        }));
        map.put(19, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_RAW_COMMAND,
        }));
        map.put(21, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_CMD_PARAM_START,
        }));
        map.put(20, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_CMD_PART,
        }));
        map.put(50, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_TYPE,
            WdlTerminalIdentifier.TERMINAL_TYPE_E,
        }));
        map.put(2, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_IMPORT,
            WdlTerminalIdentifier.TERMINAL_TASK,
            WdlTerminalIdentifier.TERMINAL_TYPE,
            WdlTerminalIdentifier.TERMINAL_TYPE_E,
            WdlTerminalIdentifier.TERMINAL_WORKFLOW,
        }));
        map.put(104, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_BOOLEAN,
            WdlTerminalIdentifier.TERMINAL_DASH,
            WdlTerminalIdentifier.TERMINAL_E,
            WdlTerminalIdentifier.TERMINAL_FLOAT,
            WdlTerminalIdentifier.TERMINAL_IDENTIFIER,
            WdlTerminalIdentifier.TERMINAL_IF,
            WdlTerminalIdentifier.TERMINAL_INTEGER,
            WdlTerminalIdentifier.TERMINAL_LBRACE,
            WdlTerminalIdentifier.TERMINAL_LPAREN,
            WdlTerminalIdentifier.TERMINAL_LSQUARE,
            WdlTerminalIdentifier.TERMINAL_NOT,
            WdlTerminalIdentifier.TERMINAL_OBJECT,
            WdlTerminalIdentifier.TERMINAL_PLUS,
            WdlTerminalIdentifier.TERMINAL_STRING,
        }));
        map.put(103, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_BOOLEAN,
            WdlTerminalIdentifier.TERMINAL_DASH,
            WdlTerminalIdentifier.TERMINAL_E,
            WdlTerminalIdentifier.TERMINAL_FLOAT,
            WdlTerminalIdentifier.TERMINAL_IDENTIFIER,
            WdlTerminalIdentifier.TERMINAL_IF,
            WdlTerminalIdentifier.TERMINAL_INTEGER,
            WdlTerminalIdentifier.TERMINAL_LBRACE,
            WdlTerminalIdentifier.TERMINAL_LPAREN,
            WdlTerminalIdentifier.TERMINAL_LSQUARE,
            WdlTerminalIdentifier.TERMINAL_NOT,
            WdlTerminalIdentifier.TERMINAL_OBJECT,
            WdlTerminalIdentifier.TERMINAL_PLUS,
            WdlTerminalIdentifier.TERMINAL_STRING,
        }));
        map.put(95, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_BOOLEAN,
            WdlTerminalIdentifier.TERMINAL_DASH,
            WdlTerminalIdentifier.TERMINAL_E,
            WdlTerminalIdentifier.TERMINAL_FLOAT,
            WdlTerminalIdentifier.TERMINAL_IDENTIFIER,
            WdlTerminalIdentifier.TERMINAL_IF,
            WdlTerminalIdentifier.TERMINAL_INTEGER,
            WdlTerminalIdentifier.TERMINAL_LBRACE,
            WdlTerminalIdentifier.TERMINAL_LPAREN,
            WdlTerminalIdentifier.TERMINAL_LSQUARE,
            WdlTerminalIdentifier.TERMINAL_NOT,
            WdlTerminalIdentifier.TERMINAL_OBJECT,
            WdlTerminalIdentifier.TERMINAL_PLUS,
            WdlTerminalIdentifier.TERMINAL_STRING,
        }));
        map.put(96, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_BOOLEAN,
            WdlTerminalIdentifier.TERMINAL_DASH,
            WdlTerminalIdentifier.TERMINAL_E,
            WdlTerminalIdentifier.TERMINAL_FLOAT,
            WdlTerminalIdentifier.TERMINAL_IDENTIFIER,
            WdlTerminalIdentifier.TERMINAL_IF,
            WdlTerminalIdentifier.TERMINAL_INTEGER,
            WdlTerminalIdentifier.TERMINAL_LBRACE,
            WdlTerminalIdentifier.TERMINAL_LPAREN,
            WdlTerminalIdentifier.TERMINAL_LSQUARE,
            WdlTerminalIdentifier.TERMINAL_NOT,
            WdlTerminalIdentifier.TERMINAL_OBJECT,
            WdlTerminalIdentifier.TERMINAL_PLUS,
            WdlTerminalIdentifier.TERMINAL_STRING,
        }));
        map.put(94, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_BOOLEAN,
            WdlTerminalIdentifier.TERMINAL_DASH,
            WdlTerminalIdentifier.TERMINAL_E,
            WdlTerminalIdentifier.TERMINAL_FLOAT,
            WdlTerminalIdentifier.TERMINAL_IDENTIFIER,
            WdlTerminalIdentifier.TERMINAL_IF,
            WdlTerminalIdentifier.TERMINAL_INTEGER,
            WdlTerminalIdentifier.TERMINAL_LBRACE,
            WdlTerminalIdentifier.TERMINAL_LPAREN,
            WdlTerminalIdentifier.TERMINAL_LSQUARE,
            WdlTerminalIdentifier.TERMINAL_NOT,
            WdlTerminalIdentifier.TERMINAL_OBJECT,
            WdlTerminalIdentifier.TERMINAL_PLUS,
            WdlTerminalIdentifier.TERMINAL_STRING,
        }));
        map.put(100, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_BOOLEAN,
            WdlTerminalIdentifier.TERMINAL_DASH,
            WdlTerminalIdentifier.TERMINAL_E,
            WdlTerminalIdentifier.TERMINAL_FLOAT,
            WdlTerminalIdentifier.TERMINAL_IDENTIFIER,
            WdlTerminalIdentifier.TERMINAL_IF,
            WdlTerminalIdentifier.TERMINAL_INTEGER,
            WdlTerminalIdentifier.TERMINAL_LBRACE,
            WdlTerminalIdentifier.TERMINAL_LPAREN,
            WdlTerminalIdentifier.TERMINAL_LSQUARE,
            WdlTerminalIdentifier.TERMINAL_NOT,
            WdlTerminalIdentifier.TERMINAL_OBJECT,
            WdlTerminalIdentifier.TERMINAL_PLUS,
            WdlTerminalIdentifier.TERMINAL_STRING,
        }));
        map.put(101, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_BOOLEAN,
            WdlTerminalIdentifier.TERMINAL_DASH,
            WdlTerminalIdentifier.TERMINAL_E,
            WdlTerminalIdentifier.TERMINAL_FLOAT,
            WdlTerminalIdentifier.TERMINAL_IDENTIFIER,
            WdlTerminalIdentifier.TERMINAL_IF,
            WdlTerminalIdentifier.TERMINAL_INTEGER,
            WdlTerminalIdentifier.TERMINAL_LBRACE,
            WdlTerminalIdentifier.TERMINAL_LPAREN,
            WdlTerminalIdentifier.TERMINAL_LSQUARE,
            WdlTerminalIdentifier.TERMINAL_NOT,
            WdlTerminalIdentifier.TERMINAL_OBJECT,
            WdlTerminalIdentifier.TERMINAL_PLUS,
            WdlTerminalIdentifier.TERMINAL_STRING,
        }));
        map.put(98, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_BOOLEAN,
            WdlTerminalIdentifier.TERMINAL_DASH,
            WdlTerminalIdentifier.TERMINAL_E,
            WdlTerminalIdentifier.TERMINAL_FLOAT,
            WdlTerminalIdentifier.TERMINAL_IDENTIFIER,
            WdlTerminalIdentifier.TERMINAL_IF,
            WdlTerminalIdentifier.TERMINAL_INTEGER,
            WdlTerminalIdentifier.TERMINAL_LBRACE,
            WdlTerminalIdentifier.TERMINAL_LPAREN,
            WdlTerminalIdentifier.TERMINAL_LSQUARE,
            WdlTerminalIdentifier.TERMINAL_NOT,
            WdlTerminalIdentifier.TERMINAL_OBJECT,
            WdlTerminalIdentifier.TERMINAL_PLUS,
            WdlTerminalIdentifier.TERMINAL_STRING,
        }));
        map.put(99, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_BOOLEAN,
            WdlTerminalIdentifier.TERMINAL_DASH,
            WdlTerminalIdentifier.TERMINAL_E,
            WdlTerminalIdentifier.TERMINAL_FLOAT,
            WdlTerminalIdentifier.TERMINAL_IDENTIFIER,
            WdlTerminalIdentifier.TERMINAL_IF,
            WdlTerminalIdentifier.TERMINAL_INTEGER,
            WdlTerminalIdentifier.TERMINAL_LBRACE,
            WdlTerminalIdentifier.TERMINAL_LPAREN,
            WdlTerminalIdentifier.TERMINAL_LSQUARE,
            WdlTerminalIdentifier.TERMINAL_NOT,
            WdlTerminalIdentifier.TERMINAL_OBJECT,
            WdlTerminalIdentifier.TERMINAL_PLUS,
            WdlTerminalIdentifier.TERMINAL_STRING,
        }));
        map.put(97, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_BOOLEAN,
            WdlTerminalIdentifier.TERMINAL_DASH,
            WdlTerminalIdentifier.TERMINAL_E,
            WdlTerminalIdentifier.TERMINAL_FLOAT,
            WdlTerminalIdentifier.TERMINAL_IDENTIFIER,
            WdlTerminalIdentifier.TERMINAL_IF,
            WdlTerminalIdentifier.TERMINAL_INTEGER,
            WdlTerminalIdentifier.TERMINAL_LBRACE,
            WdlTerminalIdentifier.TERMINAL_LPAREN,
            WdlTerminalIdentifier.TERMINAL_LSQUARE,
            WdlTerminalIdentifier.TERMINAL_NOT,
            WdlTerminalIdentifier.TERMINAL_OBJECT,
            WdlTerminalIdentifier.TERMINAL_PLUS,
            WdlTerminalIdentifier.TERMINAL_STRING,
        }));
        map.put(106, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_BOOLEAN,
            WdlTerminalIdentifier.TERMINAL_DASH,
            WdlTerminalIdentifier.TERMINAL_E,
            WdlTerminalIdentifier.TERMINAL_FLOAT,
            WdlTerminalIdentifier.TERMINAL_IDENTIFIER,
            WdlTerminalIdentifier.TERMINAL_IF,
            WdlTerminalIdentifier.TERMINAL_INTEGER,
            WdlTerminalIdentifier.TERMINAL_LBRACE,
            WdlTerminalIdentifier.TERMINAL_LPAREN,
            WdlTerminalIdentifier.TERMINAL_LSQUARE,
            WdlTerminalIdentifier.TERMINAL_NOT,
            WdlTerminalIdentifier.TERMINAL_OBJECT,
            WdlTerminalIdentifier.TERMINAL_PLUS,
            WdlTerminalIdentifier.TERMINAL_STRING,
        }));
        map.put(102, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_BOOLEAN,
            WdlTerminalIdentifier.TERMINAL_DASH,
            WdlTerminalIdentifier.TERMINAL_E,
            WdlTerminalIdentifier.TERMINAL_FLOAT,
            WdlTerminalIdentifier.TERMINAL_IDENTIFIER,
            WdlTerminalIdentifier.TERMINAL_IF,
            WdlTerminalIdentifier.TERMINAL_INTEGER,
            WdlTerminalIdentifier.TERMINAL_LBRACE,
            WdlTerminalIdentifier.TERMINAL_LPAREN,
            WdlTerminalIdentifier.TERMINAL_LSQUARE,
            WdlTerminalIdentifier.TERMINAL_NOT,
            WdlTerminalIdentifier.TERMINAL_OBJECT,
            WdlTerminalIdentifier.TERMINAL_PLUS,
            WdlTerminalIdentifier.TERMINAL_STRING,
        }));
        map.put(105, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_BOOLEAN,
            WdlTerminalIdentifier.TERMINAL_DASH,
            WdlTerminalIdentifier.TERMINAL_E,
            WdlTerminalIdentifier.TERMINAL_FLOAT,
            WdlTerminalIdentifier.TERMINAL_IDENTIFIER,
            WdlTerminalIdentifier.TERMINAL_IF,
            WdlTerminalIdentifier.TERMINAL_INTEGER,
            WdlTerminalIdentifier.TERMINAL_LBRACE,
            WdlTerminalIdentifier.TERMINAL_LPAREN,
            WdlTerminalIdentifier.TERMINAL_LSQUARE,
            WdlTerminalIdentifier.TERMINAL_NOT,
            WdlTerminalIdentifier.TERMINAL_OBJECT,
            WdlTerminalIdentifier.TERMINAL_PLUS,
            WdlTerminalIdentifier.TERMINAL_STRING,
        }));
        map.put(123, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_BOOLEAN,
        }));
        map.put(109, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_DASH,
        }));
        map.put(125, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_FLOAT,
        }));
        map.put(122, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_IDENTIFIER,
        }));
        map.put(113, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_IDENTIFIER,
        }));
        map.put(111, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_IDENTIFIER,
        }));
        map.put(112, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_IDENTIFIER,
        }));
        map.put(120, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_IF,
        }));
        map.put(124, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_INTEGER,
        }));
        map.put(118, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_LBRACE,
        }));
        map.put(119, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_LPAREN,
        }));
        map.put(116, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_LSQUARE,
        }));
        map.put(107, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_NOT,
        }));
        map.put(115, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_OBJECT,
        }));
        map.put(108, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_PLUS,
        }));
        map.put(121, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_STRING,
        }));
        map.put(86, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_IF,
        }));
        map.put(8, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_IMPORT,
        }));
        map.put(9, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_AS,
        }));
        map.put(31, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_IDENTIFIER,
        }));
        map.put(30, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_LBRACE,
        }));
        map.put(52, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_BOOLEAN,
            WdlTerminalIdentifier.TERMINAL_DASH,
            WdlTerminalIdentifier.TERMINAL_E,
            WdlTerminalIdentifier.TERMINAL_FLOAT,
            WdlTerminalIdentifier.TERMINAL_IDENTIFIER,
            WdlTerminalIdentifier.TERMINAL_IF,
            WdlTerminalIdentifier.TERMINAL_INTEGER,
            WdlTerminalIdentifier.TERMINAL_LBRACE,
            WdlTerminalIdentifier.TERMINAL_LPAREN,
            WdlTerminalIdentifier.TERMINAL_LSQUARE,
            WdlTerminalIdentifier.TERMINAL_NOT,
            WdlTerminalIdentifier.TERMINAL_OBJECT,
            WdlTerminalIdentifier.TERMINAL_PLUS,
            WdlTerminalIdentifier.TERMINAL_STRING,
        }));
        map.put(72, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_IDENTIFIER,
        }));
        map.put(32, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_META,
        }));
        map.put(47, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_LSQUARE,
        }));
        map.put(36, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_IDENTIFIER,
        }));
        map.put(35, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_LBRACE,
        }));
        map.put(45, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_LBRACE,
        }));
        map.put(43, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_LSQUARE,
        }));
        map.put(42, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_LBRACE,
        }));
        map.put(40, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_BOOLEAN,
        }));
        map.put(39, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_FLOAT,
        }));
        map.put(38, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_INTEGER,
        }));
        map.put(41, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_NULL,
        }));
        map.put(37, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_STRING,
        }));
        map.put(88, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_IDENTIFIER,
        }));
        map.put(27, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_TYPE,
            WdlTerminalIdentifier.TERMINAL_TYPE_E,
        }));
        map.put(26, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_OUTPUT,
        }));
        map.put(33, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_PARAMETER_META,
        }));
        map.put(28, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_RUNTIME,
        }));
        map.put(87, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_SCATTER,
        }));
        map.put(13, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_RAW_COMMAND,
        }));
        map.put(17, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_META,
        }));
        map.put(14, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_OUTPUT,
        }));
        map.put(16, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_PARAMETER_META,
        }));
        map.put(15, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_RUNTIME,
        }));
        map.put(51, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_EQUAL,
        }));
        map.put(12, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_TASK,
        }));
        map.put(93, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_TYPE,
        }));
        map.put(90, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_TYPE,
        }));
        map.put(92, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_TYPE,
        }));
        map.put(91, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_TYPE,
        }));
        map.put(55, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_CALL,
        }));
        map.put(56, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_TYPE,
            WdlTerminalIdentifier.TERMINAL_TYPE_E,
        }));
        map.put(58, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_IF,
        }));
        map.put(59, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_SCATTER,
        }));
        map.put(62, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_META,
        }));
        map.put(60, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_OUTPUT,
        }));
        map.put(61, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_PARAMETER_META,
        }));
        map.put(57, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_WHILE,
        }));
        map.put(84, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_META,
        }));
        map.put(76, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_TYPE,
            WdlTerminalIdentifier.TERMINAL_TYPE_E,
        }));
        map.put(77, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_FQN,
        }));
        map.put(78, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_TYPE,
            WdlTerminalIdentifier.TERMINAL_TYPE_E,
        }));
        map.put(82, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_DOT,
        }));
        map.put(81, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_FQN,
        }));
        map.put(75, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_OUTPUT,
        }));
        map.put(83, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_PARAMETER_META,
        }));
        map.put(85, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_WHILE,
        }));
        map.put(54, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_WORKFLOW,
        }));
        map.put(5, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_TYPE,
            WdlTerminalIdentifier.TERMINAL_TYPE_E,
        }));
        map.put(4, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_TASK,
        }));
        map.put(3, Arrays.asList(new TerminalIdentifier[] {
            WdlTerminalIdentifier.TERMINAL_WORKFLOW,
        }));
        rule_first = Collections.unmodifiableMap(map);
    }
    static {
        Map<Integer, List<String>> map = new HashMap<Integer, List<String>>();
        map.put(113, new ArrayList<String>());
        map.put(124, new ArrayList<String>());
        map.put(67, new ArrayList<String>());
        map.put(90, new ArrayList<String>());
        map.put(99, new ArrayList<String>());
        map.put(117, new ArrayList<String>());
        map.put(107, new ArrayList<String>());
        map.put(103, new ArrayList<String>());
        map.put(73, new ArrayList<String>());
        map.put(88, new ArrayList<String>());
        map.put(100, new ArrayList<String>());
        map.put(112, new ArrayList<String>());
        map.put(83, new ArrayList<String>());
        map.put(64, new ArrayList<String>());
        map.put(126, new ArrayList<String>());
        map.put(68, new ArrayList<String>());
        map.put(70, new ArrayList<String>());
        map.put(85, new ArrayList<String>());
        map.put(78, new ArrayList<String>());
        map.put(121, new ArrayList<String>());
        map.put(69, new ArrayList<String>());
        map.put(101, new ArrayList<String>());
        map.put(63, new ArrayList<String>());
        map.put(93, new ArrayList<String>());
        map.put(127, new ArrayList<String>());
        map.put(71, new ArrayList<String>());
        map.put(102, new ArrayList<String>());
        map.put(75, new ArrayList<String>());
        map.put(108, new ArrayList<String>());
        map.put(59, new ArrayList<String>());
        map.put(105, new ArrayList<String>());
        map.put(116, new ArrayList<String>());
        map.put(109, new ArrayList<String>());
        map.put(72, new ArrayList<String>());
        map.put(97, new ArrayList<String>());
        map.put(76, new ArrayList<String>());
        map.put(91, new ArrayList<String>());
        map.put(89, new ArrayList<String>());
        map.put(114, new ArrayList<String>());
        map.put(118, new ArrayList<String>());
        map.put(119, new ArrayList<String>());
        map.put(122, new ArrayList<String>());
        map.put(104, new ArrayList<String>());
        map.put(74, new ArrayList<String>());
        map.put(87, new ArrayList<String>());
        map.put(92, new ArrayList<String>());
        map.put(94, new ArrayList<String>());
        map.put(96, new ArrayList<String>());
        map.put(110, new ArrayList<String>());
        map.put(123, new ArrayList<String>());
        map.put(84, new ArrayList<String>());
        map.put(120, new ArrayList<String>());
        map.put(80, new ArrayList<String>());
        map.put(111, new ArrayList<String>());
        map.put(66, new ArrayList<String>());
        map.put(60, new ArrayList<String>());
        map.put(81, new ArrayList<String>());
        map.put(125, new ArrayList<String>());
        map.put(86, new ArrayList<String>());
        map.put(62, new ArrayList<String>());
        map.put(79, new ArrayList<String>());
        map.put(77, new ArrayList<String>());
        map.put(82, new ArrayList<String>());
        map.put(115, new ArrayList<String>());
        map.put(95, new ArrayList<String>());
        map.put(98, new ArrayList<String>());
        map.put(65, new ArrayList<String>());
        map.put(106, new ArrayList<String>());
        map.put(61, new ArrayList<String>());
        map.get(113).add("$_gen0 = list($import)");
        map.get(124).add("$_gen1 = list($workflow_or_task_or_decl)");
        map.get(67).add("$_gen10 = list($meta_kv, :comma)");
        map.get(90).add("$_gen11 = list($meta_value, :comma)");
        map.get(99).add("$_gen12 = $setter");
        map.get(99).add("$_gen12 = :_empty");
        map.get(117).add("$_gen13 = list($wf_body_element)");
        map.get(107).add("$_gen14 = $alias");
        map.get(107).add("$_gen14 = :_empty");
        map.get(103).add("$_gen15 = $call_body");
        map.get(103).add("$_gen15 = :_empty");
        map.get(73).add("$_gen16 = list($call_input)");
        map.get(88).add("$_gen17 = list($mapping, :comma)");
        map.get(100).add("$_gen18 = list($wf_output)");
        map.get(112).add("$_gen19 = $wf_output_wildcard");
        map.get(112).add("$_gen19 = :_empty");
        map.get(83).add("$_gen2 = $import_namespace");
        map.get(83).add("$_gen2 = :_empty");
        map.get(64).add("$_gen20 = list($type_e, :comma)");
        map.get(126).add("$_gen21 = list($e, :comma)");
        map.get(68).add("$_gen22 = list($object_kv, :comma)");
        map.get(70).add("$_gen23 = list($map_kv, :comma)");
        map.get(85).add("$_gen3 = list($declaration)");
        map.get(78).add("$_gen4 = list($sections)");
        map.get(121).add("$_gen5 = list($command_part)");
        map.get(69).add("$_gen6 = list($cmd_param_kv)");
        map.get(101).add("$_gen7 = list($output_kv)");
        map.get(63).add("$_gen8 = list($kv)");
        map.get(93).add("$_gen9 = list($meta_kv)");
        map.get(127).add("$alias = :as :identifier -> $1");
        map.get(71).add("$call = :call :fqn $_gen14 $_gen15 -> Call( task=$1, alias=$2, body=$3 )");
        map.get(102).add("$call_body = :lbrace $_gen3 $_gen16 :rbrace -> CallBody( declarations=$1, io=$2 )");
        map.get(75).add("$call_input = :input :colon $_gen17 -> Inputs( map=$2 )");
        map.get(108).add("$cmd_param = :cmd_param_start $_gen6 $e :cmd_param_end -> CommandParameter( attributes=$1, expr=$2 )");
        map.get(59).add("$cmd_param_kv = :cmd_attr_hint :identifier :equal $e -> CommandParameterAttr( key=$1, value=$3 )");
        map.get(105).add("$command = :raw_command :raw_cmd_start $_gen5 :raw_cmd_end -> RawCommand( parts=$2 )");
        map.get(116).add("$command_part = $cmd_param");
        map.get(116).add("$command_part = :cmd_part");
        map.get(109).add("$declaration = $type_e :identifier $_gen12 -> Declaration( type=$0, name=$1, expression=$2 )");
        map.get(72).add("$document = $_gen0 $_gen1 -> Namespace( imports=$0, body=$1 )");
        map.get(97).add("$e = $e :asterisk $e -> Multiply( lhs=$0, rhs=$2 )");
        map.get(97).add("$e = $e :dash $e -> Subtract( lhs=$0, rhs=$2 )");
        map.get(97).add("$e = $e :double_ampersand $e -> LogicalAnd( lhs=$0, rhs=$2 )");
        map.get(97).add("$e = $e :double_equal $e -> Equals( lhs=$0, rhs=$2 )");
        map.get(97).add("$e = $e :double_pipe $e -> LogicalOr( lhs=$0, rhs=$2 )");
        map.get(97).add("$e = $e :gt $e -> GreaterThan( lhs=$0, rhs=$2 )");
        map.get(97).add("$e = $e :gteq $e -> GreaterThanOrEqual( lhs=$0, rhs=$2 )");
        map.get(97).add("$e = $e :lt $e -> LessThan( lhs=$0, rhs=$2 )");
        map.get(97).add("$e = $e :lteq $e -> LessThanOrEqual( lhs=$0, rhs=$2 )");
        map.get(97).add("$e = $e :not_equal $e -> NotEquals( lhs=$0, rhs=$2 )");
        map.get(97).add("$e = $e :percent $e -> Remainder( lhs=$0, rhs=$2 )");
        map.get(97).add("$e = $e :plus $e -> Add( lhs=$0, rhs=$2 )");
        map.get(97).add("$e = $e :slash $e -> Divide( lhs=$0, rhs=$2 )");
        map.get(97).add("$e = :boolean");
        map.get(97).add("$e = :dash $e -> UnaryNegation( expression=$1 )");
        map.get(97).add("$e = :float");
        map.get(97).add("$e = :identifier");
        map.get(97).add("$e = :identifier <=> :dot :identifier -> MemberAccess( lhs=$0, rhs=$2 )");
        map.get(97).add("$e = :identifier <=> :lparen $_gen21 :rparen -> FunctionCall( name=$0, params=$2 )");
        map.get(97).add("$e = :identifier <=> :lsquare $e :rsquare -> ArrayOrMapLookup( lhs=$0, rhs=$2 )");
        map.get(97).add("$e = :if $e :then $e :else $e -> TernaryIf( cond=$1, iftrue=$3, iffalse=$5 )");
        map.get(97).add("$e = :integer");
        map.get(97).add("$e = :lbrace $_gen23 :rbrace -> MapLiteral( map=$1 )");
        map.get(97).add("$e = :lparen $_gen21 :rparen -> TupleLiteral( values=$1 )");
        map.get(97).add("$e = :lsquare $_gen21 :rsquare -> ArrayLiteral( values=$1 )");
        map.get(97).add("$e = :not $e -> LogicalNot( expression=$1 )");
        map.get(97).add("$e = :object :lbrace $_gen22 :rbrace -> ObjectLiteral( map=$2 )");
        map.get(97).add("$e = :plus $e -> UnaryPlus( expression=$1 )");
        map.get(97).add("$e = :string");
        map.get(76).add("$if_stmt = :if :lparen $e :rparen :lbrace $_gen13 :rbrace -> If( expression=$2, body=$5 )");
        map.get(91).add("$import = :import :string $_gen2 -> Import( uri=$1, namespace=$2 )");
        map.get(89).add("$import_namespace = :as :identifier -> $1");
        map.get(114).add("$kv = :identifier :colon $e -> RuntimeAttribute( key=$0, value=$2 )");
        map.get(118).add("$map = :lbrace $_gen8 :rbrace -> $1");
        map.get(119).add("$map_kv = $e :colon $e -> MapLiteralKv( key=$0, value=$2 )");
        map.get(122).add("$mapping = :identifier :equal $e -> IOMapping( key=$0, value=$2 )");
        map.get(104).add("$meta = :meta $meta_map -> Meta( map=$1 )");
        map.get(74).add("$meta_array = :lsquare $_gen11 :rsquare");
        map.get(87).add("$meta_kv = :identifier :colon $meta_value");
        map.get(92).add("$meta_map = :lbrace $_gen9 :rbrace -> $1");
        map.get(94).add("$meta_object = :lbrace $_gen10 :rbrace");
        map.get(96).add("$meta_value = $meta_array");
        map.get(96).add("$meta_value = $meta_object");
        map.get(96).add("$meta_value = :boolean");
        map.get(96).add("$meta_value = :float");
        map.get(96).add("$meta_value = :integer");
        map.get(96).add("$meta_value = :null");
        map.get(96).add("$meta_value = :string");
        map.get(110).add("$object_kv = :identifier :colon $e -> ObjectKV( key=$0, value=$2 )");
        map.get(123).add("$output_kv = $type_e :identifier :equal $e -> Output( type=$0, name=$1, expression=$3 )");
        map.get(84).add("$outputs = :output :lbrace $_gen7 :rbrace -> Outputs( attributes=$2 )");
        map.get(120).add("$parameter_meta = :parameter_meta $meta_map -> ParameterMeta( map=$1 )");
        map.get(80).add("$runtime = :runtime $map -> Runtime( map=$1 )");
        map.get(111).add("$scatter = :scatter :lparen :identifier :in $e :rparen :lbrace $_gen13 :rbrace -> Scatter( item=$2, collection=$4, body=$7 )");
        map.get(66).add("$sections = $command");
        map.get(66).add("$sections = $meta");
        map.get(66).add("$sections = $outputs");
        map.get(66).add("$sections = $parameter_meta");
        map.get(66).add("$sections = $runtime");
        map.get(60).add("$setter = :equal $e -> $1");
        map.get(81).add("$task = :task :identifier :lbrace $_gen3 $_gen4 :rbrace -> Task( name=$1, declarations=$3, sections=$4 )");
        map.get(125).add("$type_e = :type");
        map.get(125).add("$type_e = :type <=> :lsquare $_gen20 :rsquare -> Type( name=$0, subtype=$2 )");
        map.get(125).add("$type_e = :type <=> :plus -> NonEmptyType( innerType=$0 )");
        map.get(125).add("$type_e = :type <=> :qmark -> OptionalType( innerType=$0 )");
        map.get(86).add("$wf_body_element = $call");
        map.get(86).add("$wf_body_element = $declaration");
        map.get(86).add("$wf_body_element = $if_stmt");
        map.get(86).add("$wf_body_element = $scatter");
        map.get(86).add("$wf_body_element = $wf_meta");
        map.get(86).add("$wf_body_element = $wf_outputs");
        map.get(86).add("$wf_body_element = $wf_parameter_meta");
        map.get(86).add("$wf_body_element = $while_loop");
        map.get(62).add("$wf_meta = :meta $map -> Meta( map=$1 )");
        map.get(79).add("$wf_output = $wf_output_declaration_syntax");
        map.get(79).add("$wf_output = $wf_output_wildcard_syntax");
        map.get(77).add("$wf_output_declaration_syntax = $type_e :identifier :equal $e -> WorkflowOutputDeclaration( type=$0, name=$1, expression=$3 )");
        map.get(82).add("$wf_output_wildcard = :dot :asterisk -> $1");
        map.get(115).add("$wf_output_wildcard_syntax = :fqn $_gen19 -> WorkflowOutputWildcard( fqn=$0, wildcard=$1 )");
        map.get(95).add("$wf_outputs = :output :lbrace $_gen18 :rbrace -> WorkflowOutputs( outputs=$2 )");
        map.get(98).add("$wf_parameter_meta = :parameter_meta $map -> ParameterMeta( map=$1 )");
        map.get(65).add("$while_loop = :while :lparen $e :rparen :lbrace $_gen13 :rbrace -> WhileLoop( expression=$2, body=$5 )");
        map.get(106).add("$workflow = :workflow :identifier :lbrace $_gen13 :rbrace -> Workflow( name=$1, body=$3 )");
        map.get(61).add("$workflow_or_task_or_decl = $declaration");
        map.get(61).add("$workflow_or_task_or_decl = $task");
        map.get(61).add("$workflow_or_task_or_decl = $workflow");
        nonterminal_rules = Collections.unmodifiableMap(map);
    }
    static {
        Map<Integer, String> map = new HashMap<Integer, String>();
        map.put(new Integer(0), "$_gen0 = list($import)");
        map.put(new Integer(1), "$_gen1 = list($workflow_or_task_or_decl)");
        map.put(new Integer(44), "$_gen10 = list($meta_kv, :comma)");
        map.put(new Integer(46), "$_gen11 = list($meta_value, :comma)");
        map.put(new Integer(48), "$_gen12 = $setter");
        map.put(new Integer(49), "$_gen12 = :_empty");
        map.put(new Integer(53), "$_gen13 = list($wf_body_element)");
        map.put(new Integer(63), "$_gen14 = $alias");
        map.put(new Integer(64), "$_gen14 = :_empty");
        map.put(new Integer(65), "$_gen15 = $call_body");
        map.put(new Integer(66), "$_gen15 = :_empty");
        map.put(new Integer(68), "$_gen16 = list($call_input)");
        map.put(new Integer(70), "$_gen17 = list($mapping, :comma)");
        map.put(new Integer(74), "$_gen18 = list($wf_output)");
        map.put(new Integer(79), "$_gen19 = $wf_output_wildcard");
        map.put(new Integer(80), "$_gen19 = :_empty");
        map.put(new Integer(6), "$_gen2 = $import_namespace");
        map.put(new Integer(7), "$_gen2 = :_empty");
        map.put(new Integer(89), "$_gen20 = list($type_e, :comma)");
        map.put(new Integer(110), "$_gen21 = list($e, :comma)");
        map.put(new Integer(114), "$_gen22 = list($object_kv, :comma)");
        map.put(new Integer(117), "$_gen23 = list($map_kv, :comma)");
        map.put(new Integer(10), "$_gen3 = list($declaration)");
        map.put(new Integer(11), "$_gen4 = list($sections)");
        map.put(new Integer(18), "$_gen5 = list($command_part)");
        map.put(new Integer(22), "$_gen6 = list($cmd_param_kv)");
        map.put(new Integer(25), "$_gen7 = list($output_kv)");
        map.put(new Integer(29), "$_gen8 = list($kv)");
        map.put(new Integer(34), "$_gen9 = list($meta_kv)");
        map.put(new Integer(73), "$alias = :as :identifier -> $1");
        map.put(new Integer(67), "$call = :call :fqn $_gen14 $_gen15 -> Call( task=$1, alias=$2, body=$3 )");
        map.put(new Integer(69), "$call_body = :lbrace $_gen3 $_gen16 :rbrace -> CallBody( declarations=$1, io=$2 )");
        map.put(new Integer(71), "$call_input = :input :colon $_gen17 -> Inputs( map=$2 )");
        map.put(new Integer(23), "$cmd_param = :cmd_param_start $_gen6 $e :cmd_param_end -> CommandParameter( attributes=$1, expr=$2 )");
        map.put(new Integer(24), "$cmd_param_kv = :cmd_attr_hint :identifier :equal $e -> CommandParameterAttr( key=$1, value=$3 )");
        map.put(new Integer(19), "$command = :raw_command :raw_cmd_start $_gen5 :raw_cmd_end -> RawCommand( parts=$2 )");
        map.put(new Integer(21), "$command_part = $cmd_param");
        map.put(new Integer(20), "$command_part = :cmd_part");
        map.put(new Integer(50), "$declaration = $type_e :identifier $_gen12 -> Declaration( type=$0, name=$1, expression=$2 )");
        map.put(new Integer(2), "$document = $_gen0 $_gen1 -> Namespace( imports=$0, body=$1 )");
        map.put(new Integer(104), "$e = $e :asterisk $e -> Multiply( lhs=$0, rhs=$2 )");
        map.put(new Integer(103), "$e = $e :dash $e -> Subtract( lhs=$0, rhs=$2 )");
        map.put(new Integer(95), "$e = $e :double_ampersand $e -> LogicalAnd( lhs=$0, rhs=$2 )");
        map.put(new Integer(96), "$e = $e :double_equal $e -> Equals( lhs=$0, rhs=$2 )");
        map.put(new Integer(94), "$e = $e :double_pipe $e -> LogicalOr( lhs=$0, rhs=$2 )");
        map.put(new Integer(100), "$e = $e :gt $e -> GreaterThan( lhs=$0, rhs=$2 )");
        map.put(new Integer(101), "$e = $e :gteq $e -> GreaterThanOrEqual( lhs=$0, rhs=$2 )");
        map.put(new Integer(98), "$e = $e :lt $e -> LessThan( lhs=$0, rhs=$2 )");
        map.put(new Integer(99), "$e = $e :lteq $e -> LessThanOrEqual( lhs=$0, rhs=$2 )");
        map.put(new Integer(97), "$e = $e :not_equal $e -> NotEquals( lhs=$0, rhs=$2 )");
        map.put(new Integer(106), "$e = $e :percent $e -> Remainder( lhs=$0, rhs=$2 )");
        map.put(new Integer(102), "$e = $e :plus $e -> Add( lhs=$0, rhs=$2 )");
        map.put(new Integer(105), "$e = $e :slash $e -> Divide( lhs=$0, rhs=$2 )");
        map.put(new Integer(123), "$e = :boolean");
        map.put(new Integer(109), "$e = :dash $e -> UnaryNegation( expression=$1 )");
        map.put(new Integer(125), "$e = :float");
        map.put(new Integer(122), "$e = :identifier");
        map.put(new Integer(113), "$e = :identifier <=> :dot :identifier -> MemberAccess( lhs=$0, rhs=$2 )");
        map.put(new Integer(111), "$e = :identifier <=> :lparen $_gen21 :rparen -> FunctionCall( name=$0, params=$2 )");
        map.put(new Integer(112), "$e = :identifier <=> :lsquare $e :rsquare -> ArrayOrMapLookup( lhs=$0, rhs=$2 )");
        map.put(new Integer(120), "$e = :if $e :then $e :else $e -> TernaryIf( cond=$1, iftrue=$3, iffalse=$5 )");
        map.put(new Integer(124), "$e = :integer");
        map.put(new Integer(118), "$e = :lbrace $_gen23 :rbrace -> MapLiteral( map=$1 )");
        map.put(new Integer(119), "$e = :lparen $_gen21 :rparen -> TupleLiteral( values=$1 )");
        map.put(new Integer(116), "$e = :lsquare $_gen21 :rsquare -> ArrayLiteral( values=$1 )");
        map.put(new Integer(107), "$e = :not $e -> LogicalNot( expression=$1 )");
        map.put(new Integer(115), "$e = :object :lbrace $_gen22 :rbrace -> ObjectLiteral( map=$2 )");
        map.put(new Integer(108), "$e = :plus $e -> UnaryPlus( expression=$1 )");
        map.put(new Integer(121), "$e = :string");
        map.put(new Integer(86), "$if_stmt = :if :lparen $e :rparen :lbrace $_gen13 :rbrace -> If( expression=$2, body=$5 )");
        map.put(new Integer(8), "$import = :import :string $_gen2 -> Import( uri=$1, namespace=$2 )");
        map.put(new Integer(9), "$import_namespace = :as :identifier -> $1");
        map.put(new Integer(31), "$kv = :identifier :colon $e -> RuntimeAttribute( key=$0, value=$2 )");
        map.put(new Integer(30), "$map = :lbrace $_gen8 :rbrace -> $1");
        map.put(new Integer(52), "$map_kv = $e :colon $e -> MapLiteralKv( key=$0, value=$2 )");
        map.put(new Integer(72), "$mapping = :identifier :equal $e -> IOMapping( key=$0, value=$2 )");
        map.put(new Integer(32), "$meta = :meta $meta_map -> Meta( map=$1 )");
        map.put(new Integer(47), "$meta_array = :lsquare $_gen11 :rsquare");
        map.put(new Integer(36), "$meta_kv = :identifier :colon $meta_value");
        map.put(new Integer(35), "$meta_map = :lbrace $_gen9 :rbrace -> $1");
        map.put(new Integer(45), "$meta_object = :lbrace $_gen10 :rbrace");
        map.put(new Integer(43), "$meta_value = $meta_array");
        map.put(new Integer(42), "$meta_value = $meta_object");
        map.put(new Integer(40), "$meta_value = :boolean");
        map.put(new Integer(39), "$meta_value = :float");
        map.put(new Integer(38), "$meta_value = :integer");
        map.put(new Integer(41), "$meta_value = :null");
        map.put(new Integer(37), "$meta_value = :string");
        map.put(new Integer(88), "$object_kv = :identifier :colon $e -> ObjectKV( key=$0, value=$2 )");
        map.put(new Integer(27), "$output_kv = $type_e :identifier :equal $e -> Output( type=$0, name=$1, expression=$3 )");
        map.put(new Integer(26), "$outputs = :output :lbrace $_gen7 :rbrace -> Outputs( attributes=$2 )");
        map.put(new Integer(33), "$parameter_meta = :parameter_meta $meta_map -> ParameterMeta( map=$1 )");
        map.put(new Integer(28), "$runtime = :runtime $map -> Runtime( map=$1 )");
        map.put(new Integer(87), "$scatter = :scatter :lparen :identifier :in $e :rparen :lbrace $_gen13 :rbrace -> Scatter( item=$2, collection=$4, body=$7 )");
        map.put(new Integer(13), "$sections = $command");
        map.put(new Integer(17), "$sections = $meta");
        map.put(new Integer(14), "$sections = $outputs");
        map.put(new Integer(16), "$sections = $parameter_meta");
        map.put(new Integer(15), "$sections = $runtime");
        map.put(new Integer(51), "$setter = :equal $e -> $1");
        map.put(new Integer(12), "$task = :task :identifier :lbrace $_gen3 $_gen4 :rbrace -> Task( name=$1, declarations=$3, sections=$4 )");
        map.put(new Integer(93), "$type_e = :type");
        map.put(new Integer(90), "$type_e = :type <=> :lsquare $_gen20 :rsquare -> Type( name=$0, subtype=$2 )");
        map.put(new Integer(92), "$type_e = :type <=> :plus -> NonEmptyType( innerType=$0 )");
        map.put(new Integer(91), "$type_e = :type <=> :qmark -> OptionalType( innerType=$0 )");
        map.put(new Integer(55), "$wf_body_element = $call");
        map.put(new Integer(56), "$wf_body_element = $declaration");
        map.put(new Integer(58), "$wf_body_element = $if_stmt");
        map.put(new Integer(59), "$wf_body_element = $scatter");
        map.put(new Integer(62), "$wf_body_element = $wf_meta");
        map.put(new Integer(60), "$wf_body_element = $wf_outputs");
        map.put(new Integer(61), "$wf_body_element = $wf_parameter_meta");
        map.put(new Integer(57), "$wf_body_element = $while_loop");
        map.put(new Integer(84), "$wf_meta = :meta $map -> Meta( map=$1 )");
        map.put(new Integer(76), "$wf_output = $wf_output_declaration_syntax");
        map.put(new Integer(77), "$wf_output = $wf_output_wildcard_syntax");
        map.put(new Integer(78), "$wf_output_declaration_syntax = $type_e :identifier :equal $e -> WorkflowOutputDeclaration( type=$0, name=$1, expression=$3 )");
        map.put(new Integer(82), "$wf_output_wildcard = :dot :asterisk -> $1");
        map.put(new Integer(81), "$wf_output_wildcard_syntax = :fqn $_gen19 -> WorkflowOutputWildcard( fqn=$0, wildcard=$1 )");
        map.put(new Integer(75), "$wf_outputs = :output :lbrace $_gen18 :rbrace -> WorkflowOutputs( outputs=$2 )");
        map.put(new Integer(83), "$wf_parameter_meta = :parameter_meta $map -> ParameterMeta( map=$1 )");
        map.put(new Integer(85), "$while_loop = :while :lparen $e :rparen :lbrace $_gen13 :rbrace -> WhileLoop( expression=$2, body=$5 )");
        map.put(new Integer(54), "$workflow = :workflow :identifier :lbrace $_gen13 :rbrace -> Workflow( name=$1, body=$3 )");
        map.put(new Integer(5), "$workflow_or_task_or_decl = $declaration");
        map.put(new Integer(4), "$workflow_or_task_or_decl = $task");
        map.put(new Integer(3), "$workflow_or_task_or_decl = $workflow");
        rules = Collections.unmodifiableMap(map);
    }
    public static boolean is_terminal(int id) {
        return 0 <= id && id <= 58;
    }
    public ParseTree parse(TokenStream tokens) throws SyntaxError {
        return parse(tokens, new DefaultSyntaxErrorFormatter());
    }
    public ParseTree parse(List<Terminal> tokens) throws SyntaxError {
        return parse(new TokenStream(tokens));
    }
    public ParseTree parse(TokenStream tokens, SyntaxErrorFormatter error_formatter) throws SyntaxError {
        ParserContext ctx = new ParserContext(tokens, error_formatter);
        ParseTree tree = parse_document(ctx);
        if (ctx.tokens.current() != null) {
            StackTraceElement[] stack = Thread.currentThread().getStackTrace();
            throw new SyntaxError(ctx.error_formatter.excessTokens(stack[1].getMethodName(), ctx.tokens.current()));
        }
        return tree;
    }
    public ParseTree parse(List<Terminal> tokens, SyntaxErrorFormatter error_formatter) throws SyntaxError {
        return parse(new TokenStream(tokens), error_formatter);
    }
    private static Terminal expect(ParserContext ctx, TerminalIdentifier expecting) throws SyntaxError {
        Terminal current = ctx.tokens.current();
        if (current == null) {
            throw new SyntaxError(ctx.error_formatter.noMoreTokens(ctx.nonterminal, expecting, ctx.tokens.last()));
        }
        if (current.getId() != expecting.id()) {
            ArrayList<TerminalIdentifier> expectedList = new ArrayList<TerminalIdentifier>();
            expectedList.add(expecting);
            throw new SyntaxError(ctx.error_formatter.unexpectedSymbol(ctx.nonterminal, current, expectedList, ctx.rule));
        }
        Terminal next = ctx.tokens.advance();
        if ( next != null && !is_terminal(next.getId()) ) {
            throw new SyntaxError(ctx.error_formatter.invalidTerminal(ctx.nonterminal, next));
        }
        return current;
    }
    private static Map<Integer, Integer> infix_binding_power_e;
    private static Map<Integer, Integer> prefix_binding_power_e;
    static {
        Map<Integer, Integer> map = new HashMap<Integer, Integer>();
        map.put(41, 4000); /* $e = $e :double_pipe $e -> LogicalOr( lhs=$0, rhs=$2 ) */
        map.put(27, 5000); /* $e = $e :double_ampersand $e -> LogicalAnd( lhs=$0, rhs=$2 ) */
        map.put(35, 6000); /* $e = $e :double_equal $e -> Equals( lhs=$0, rhs=$2 ) */
        map.put(36, 6000); /* $e = $e :not_equal $e -> NotEquals( lhs=$0, rhs=$2 ) */
        map.put(14, 7000); /* $e = $e :lt $e -> LessThan( lhs=$0, rhs=$2 ) */
        map.put(47, 7000); /* $e = $e :lteq $e -> LessThanOrEqual( lhs=$0, rhs=$2 ) */
        map.put(39, 7000); /* $e = $e :gt $e -> GreaterThan( lhs=$0, rhs=$2 ) */
        map.put(9, 7000); /* $e = $e :gteq $e -> GreaterThanOrEqual( lhs=$0, rhs=$2 ) */
        map.put(2, 8000); /* $e = $e :plus $e -> Add( lhs=$0, rhs=$2 ) */
        map.put(42, 8000); /* $e = $e :dash $e -> Subtract( lhs=$0, rhs=$2 ) */
        map.put(25, 9000); /* $e = $e :asterisk $e -> Multiply( lhs=$0, rhs=$2 ) */
        map.put(19, 9000); /* $e = $e :slash $e -> Divide( lhs=$0, rhs=$2 ) */
        map.put(20, 9000); /* $e = $e :percent $e -> Remainder( lhs=$0, rhs=$2 ) */
        map.put(8, 11000); /* $e = :identifier <=> :lparen list($e, :comma) :rparen -> FunctionCall( name=$0, params=$2 ) */
        map.put(7, 12000); /* $e = :identifier <=> :lsquare $e :rsquare -> ArrayOrMapLookup( lhs=$0, rhs=$2 ) */
        map.put(33, 13000); /* $e = :identifier <=> :dot :identifier -> MemberAccess( lhs=$0, rhs=$2 ) */
        infix_binding_power_e = Collections.unmodifiableMap(map);
    }
    static {
        Map<Integer, Integer> map = new HashMap<Integer, Integer>();
        map.put(51, 10000); /* $e = :not $e -> LogicalNot( expression=$1 ) */
        map.put(2, 10000); /* $e = :plus $e -> UnaryPlus( expression=$1 ) */
        map.put(42, 10000); /* $e = :dash $e -> UnaryNegation( expression=$1 ) */
        prefix_binding_power_e = Collections.unmodifiableMap(map);
    }
    static int get_infix_binding_power_e(int terminal_id) {
        if (infix_binding_power_e.containsKey(terminal_id)) {
            return infix_binding_power_e.get(terminal_id);
        }
        return 0;
    }
    static int get_prefix_binding_power_e(int terminal_id) {
        if (prefix_binding_power_e.containsKey(terminal_id)) {
            return prefix_binding_power_e.get(terminal_id);
        }
        return 0;
    }
    public ParseTree parse_e(List<Terminal> tokens, SyntaxErrorFormatter error_formatter) throws SyntaxError {
        ParserContext ctx = new ParserContext(new TokenStream(tokens), error_formatter);
        return parse_e_internal(ctx, 0);
    }
    public static ParseTree parse_e(ParserContext ctx) throws SyntaxError {
        return parse_e_internal(ctx, 0);
    }
    public static ParseTree parse_e_internal(ParserContext ctx, int rbp) throws SyntaxError {
        ParseTree left = nud_e(ctx);
        if ( left instanceof ParseTree ) {
            left.setExpr(true);
            left.setNud(true);
        }
        while (ctx.tokens.current() != null && rbp < get_infix_binding_power_e(ctx.tokens.current().getId())) {
            left = led_e(left, ctx);
        }
        if (left != null) {
            left.setExpr(true);
        }
        return left;
    }
    private static ParseTree nud_e(ParserContext ctx) throws SyntaxError {
        ParseTree tree = new ParseTree( new NonTerminal(97, "e") );
        Terminal current = ctx.tokens.current();
        ctx.nonterminal = "e";
        if (current == null) {
            return tree;
        }
        else if (rule_first.get(107).contains(terminal_map.get(current.getId()))) {
            /* (107) $e = :not $e -> LogicalNot( expression=$1 ) */
            ctx.rule = rules.get(107);
            LinkedHashMap<String, Integer> parameters = new LinkedHashMap<String, Integer>();
            parameters.put("expression", 1);
            tree.setAstTransformation(new AstTransformNodeCreator("LogicalNot", parameters));
            tree.setNudMorphemeCount(2);
            tree.add(expect(ctx, WdlTerminalIdentifier.TERMINAL_NOT));
            tree.add(parse_e_internal(ctx, get_prefix_binding_power_e(51)));
            tree.setPrefix(true);
        }
        else if (rule_first.get(108).contains(terminal_map.get(current.getId()))) {
            /* (108) $e = :plus $e -> UnaryPlus( expression=$1 ) */
            ctx.rule = rules.get(108);
            LinkedHashMap<String, Integer> parameters = new LinkedHashMap<String, Integer>();
            parameters.put("expression", 1);
            tree.setAstTransformation(new AstTransformNodeCreator("UnaryPlus", parameters));
            tree.setNudMorphemeCount(2);
            tree.add(expect(ctx, WdlTerminalIdentifier.TERMINAL_PLUS));
            tree.add(parse_e_internal(ctx, get_prefix_binding_power_e(2)));
            tree.setPrefix(true);
        }
        else if (rule_first.get(109).contains(terminal_map.get(current.getId()))) {
            /* (109) $e = :dash $e -> UnaryNegation( expression=$1 ) */
            ctx.rule = rules.get(109);
            LinkedHashMap<String, Integer> parameters = new LinkedHashMap<String, Integer>();
            parameters.put("expression", 1);
            tree.setAstTransformation(new AstTransformNodeCreator("UnaryNegation", parameters));
            tree.setNudMorphemeCount(2);
            tree.add(expect(ctx, WdlTerminalIdentifier.TERMINAL_DASH));
            tree.add(parse_e_internal(ctx, get_prefix_binding_power_e(42)));
            tree.setPrefix(true);
        }
        else if (rule_first.get(111).contains(terminal_map.get(current.getId()))) {
            /* (111) $e = :identifier <=> :lparen $_gen21 :rparen -> FunctionCall( name=$0, params=$2 ) */
            ctx.rule = rules.get(111);
            tree.setAstTransformation(new AstTransformSubstitution(0));
            tree.setNudMorphemeCount(1);
            tree.add(expect(ctx, WdlTerminalIdentifier.TERMINAL_IDENTIFIER));
        }
        else if (rule_first.get(112).contains(terminal_map.get(current.getId()))) {
            /* (112) $e = :identifier <=> :lsquare $e :rsquare -> ArrayOrMapLookup( lhs=$0, rhs=$2 ) */
            ctx.rule = rules.get(112);
            tree.setAstTransformation(new AstTransformSubstitution(0));
            tree.setNudMorphemeCount(1);
            tree.add(expect(ctx, WdlTerminalIdentifier.TERMINAL_IDENTIFIER));
        }
        else if (rule_first.get(113).contains(terminal_map.get(current.getId()))) {
            /* (113) $e = :identifier <=> :dot :identifier -> MemberAccess( lhs=$0, rhs=$2 ) */
            ctx.rule = rules.get(113);
            tree.setAstTransformation(new AstTransformSubstitution(0));
            tree.setNudMorphemeCount(1);
            tree.add(expect(ctx, WdlTerminalIdentifier.TERMINAL_IDENTIFIER));
        }
        else if (rule_first.get(115).contains(terminal_map.get(current.getId()))) {
            /* (115) $e = :object :lbrace $_gen22 :rbrace -> ObjectLiteral( map=$2 ) */
            ctx.rule = rules.get(115);
            LinkedHashMap<String, Integer> parameters = new LinkedHashMap<String, Integer>();
            parameters.put("map", 2);
            tree.setAstTransformation(new AstTransformNodeCreator("ObjectLiteral", parameters));
            tree.setNudMorphemeCount(4);
            tree.add(expect(ctx, WdlTerminalIdentifier.TERMINAL_OBJECT));
            tree.add(expect(ctx, WdlTerminalIdentifier.TERMINAL_LBRACE));
            tree.add(parse__gen22(ctx));
            tree.add(expect(ctx, WdlTerminalIdentifier.TERMINAL_RBRACE));
        }
        else if (rule_first.get(116).contains(terminal_map.get(current.getId()))) {
            /* (116) $e = :lsquare $_gen21 :rsquare -> ArrayLiteral( values=$1 ) */
            ctx.rule = rules.get(116);
            LinkedHashMap<String, Integer> parameters = new LinkedHashMap<String, Integer>();
            parameters.put("values", 1);
            tree.setAstTransformation(new AstTransformNodeCreator("ArrayLiteral", parameters));
            tree.setNudMorphemeCount(3);
            tree.add(expect(ctx, WdlTerminalIdentifier.TERMINAL_LSQUARE));
            tree.add(parse__gen21(ctx));
            tree.add(expect(ctx, WdlTerminalIdentifier.TERMINAL_RSQUARE));
        }
        else if (rule_first.get(118).contains(terminal_map.get(current.getId()))) {
            /* (118) $e = :lbrace $_gen23 :rbrace -> MapLiteral( map=$1 ) */
            ctx.rule = rules.get(118);
            LinkedHashMap<String, Integer> parameters = new LinkedHashMap<String, Integer>();
            parameters.put("map", 1);
            tree.setAstTransformation(new AstTransformNodeCreator("MapLiteral", parameters));
            tree.setNudMorphemeCount(3);
            tree.add(expect(ctx, WdlTerminalIdentifier.TERMINAL_LBRACE));
            tree.add(parse__gen23(ctx));
            tree.add(expect(ctx, WdlTerminalIdentifier.TERMINAL_RBRACE));
        }
        else if (rule_first.get(119).contains(terminal_map.get(current.getId()))) {
            /* (119) $e = :lparen $_gen21 :rparen -> TupleLiteral( values=$1 ) */
            ctx.rule = rules.get(119);
            LinkedHashMap<String, Integer> parameters = new LinkedHashMap<String, Integer>();
            parameters.put("values", 1);
            tree.setAstTransformation(new AstTransformNodeCreator("TupleLiteral", parameters));
            tree.setNudMorphemeCount(3);
            tree.add(expect(ctx, WdlTerminalIdentifier.TERMINAL_LPAREN));
            tree.add(parse__gen21(ctx));
            tree.add(expect(ctx, WdlTerminalIdentifier.TERMINAL_RPAREN));
        }
        else if (rule_first.get(120).contains(terminal_map.get(current.getId()))) {
            /* (120) $e = :if $e :then $e :else $e -> TernaryIf( cond=$1, iftrue=$3, iffalse=$5 ) */
            ctx.rule = rules.get(120);
            LinkedHashMap<String, Integer> parameters = new LinkedHashMap<String, Integer>();
            parameters.put("cond", 1);
            parameters.put("iftrue", 3);
            parameters.put("iffalse", 5);
            tree.setAstTransformation(new AstTransformNodeCreator("TernaryIf", parameters));
            tree.setNudMorphemeCount(6);
            tree.add(expect(ctx, WdlTerminalIdentifier.TERMINAL_IF));
            tree.add(parse_e(ctx));
            tree.add(expect(ctx, WdlTerminalIdentifier.TERMINAL_THEN));
            tree.add(parse_e(ctx));
            tree.add(expect(ctx, WdlTerminalIdentifier.TERMINAL_ELSE));
            tree.add(parse_e(ctx));
        }
        else if (rule_first.get(121).contains(terminal_map.get(current.getId()))) {
            /* (121) $e = :string */
            ctx.rule = rules.get(121);
            tree.setAstTransformation(new AstTransformSubstitution(0));
            tree.setNudMorphemeCount(1);
            tree.add(expect(ctx, WdlTerminalIdentifier.TERMINAL_STRING));
        }
        else if (rule_first.get(122).contains(terminal_map.get(current.getId()))) {
            /* (122) $e = :identifier */
            ctx.rule = rules.get(122);
            tree.setAstTransformation(new AstTransformSubstitution(0));
            tree.setNudMorphemeCount(1);
            tree.add(expect(ctx, WdlTerminalIdentifier.TERMINAL_IDENTIFIER));
        }
        else if (rule_first.get(123).contains(terminal_map.get(current.getId()))) {
            /* (123) $e = :boolean */
            ctx.rule = rules.get(123);
            tree.setAstTransformation(new AstTransformSubstitution(0));
            tree.setNudMorphemeCount(1);
            tree.add(expect(ctx, WdlTerminalIdentifier.TERMINAL_BOOLEAN));
        }
        else if (rule_first.get(124).contains(terminal_map.get(current.getId()))) {
            /* (124) $e = :integer */
            ctx.rule = rules.get(124);
            tree.setAstTransformation(new AstTransformSubstitution(0));
            tree.setNudMorphemeCount(1);
            tree.add(expect(ctx, WdlTerminalIdentifier.TERMINAL_INTEGER));
        }
        else if (rule_first.get(125).contains(terminal_map.get(current.getId()))) {
            /* (125) $e = :float */
            ctx.rule = rules.get(125);
            tree.setAstTransformation(new AstTransformSubstitution(0));
            tree.setNudMorphemeCount(1);
            tree.add(expect(ctx, WdlTerminalIdentifier.TERMINAL_FLOAT));
        }
        return tree;
    }
    private static ParseTree led_e(ParseTree left, ParserContext ctx) throws SyntaxError {
        ParseTree tree = new ParseTree( new NonTerminal(97, "e") );
        Terminal current = ctx.tokens.current();
        ctx.nonterminal = "e";
        int modifier;
        if (current.getId() == 41) {
            /* $e = $e :double_pipe $e -> LogicalOr( lhs=$0, rhs=$2 ) */
            ctx.rule = rules.get(94);
            LinkedHashMap<String, Integer> parameters = new LinkedHashMap<String, Integer>();
            parameters.put("lhs", 0);
            parameters.put("rhs", 2);
            tree.setAstTransformation(new AstTransformNodeCreator("LogicalOr", parameters));
            tree.setExprNud(true);
            tree.add(left);
            tree.add(expect(ctx, WdlTerminalIdentifier.TERMINAL_DOUBLE_PIPE));
            modifier = 0;
            tree.setInfix(true);
            tree.add(parse_e_internal(ctx, get_infix_binding_power_e(41) - modifier));
            return tree;
        }
        if (current.getId() == 27) {
            /* $e = $e :double_ampersand $e -> LogicalAnd( lhs=$0, rhs=$2 ) */
            ctx.rule = rules.get(95);
            LinkedHashMap<String, Integer> parameters = new LinkedHashMap<String, Integer>();
            parameters.put("lhs", 0);
            parameters.put("rhs", 2);
            tree.setAstTransformation(new AstTransformNodeCreator("LogicalAnd", parameters));
            tree.setExprNud(true);
            tree.add(left);
            tree.add(expect(ctx, WdlTerminalIdentifier.TERMINAL_DOUBLE_AMPERSAND));
            modifier = 0;
            tree.setInfix(true);
            tree.add(parse_e_internal(ctx, get_infix_binding_power_e(27) - modifier));
            return tree;
        }
        if (current.getId() == 35) {
            /* $e = $e :double_equal $e -> Equals( lhs=$0, rhs=$2 ) */
            ctx.rule = rules.get(96);
            LinkedHashMap<String, Integer> parameters = new LinkedHashMap<String, Integer>();
            parameters.put("lhs", 0);
            parameters.put("rhs", 2);
            tree.setAstTransformation(new AstTransformNodeCreator("Equals", parameters));
            tree.setExprNud(true);
            tree.add(left);
            tree.add(expect(ctx, WdlTerminalIdentifier.TERMINAL_DOUBLE_EQUAL));
            modifier = 0;
            tree.setInfix(true);
            tree.add(parse_e_internal(ctx, get_infix_binding_power_e(35) - modifier));
            return tree;
        }
        if (current.getId() == 36) {
            /* $e = $e :not_equal $e -> NotEquals( lhs=$0, rhs=$2 ) */
            ctx.rule = rules.get(97);
            LinkedHashMap<String, Integer> parameters = new LinkedHashMap<String, Integer>();
            parameters.put("lhs", 0);
            parameters.put("rhs", 2);
            tree.setAstTransformation(new AstTransformNodeCreator("NotEquals", parameters));
            tree.setExprNud(true);
            tree.add(left);
            tree.add(expect(ctx, WdlTerminalIdentifier.TERMINAL_NOT_EQUAL));
            modifier = 0;
            tree.setInfix(true);
            tree.add(parse_e_internal(ctx, get_infix_binding_power_e(36) - modifier));
            return tree;
        }
        if (current.getId() == 14) {
            /* $e = $e :lt $e -> LessThan( lhs=$0, rhs=$2 ) */
            ctx.rule = rules.get(98);
            LinkedHashMap<String, Integer> parameters = new LinkedHashMap<String, Integer>();
            parameters.put("lhs", 0);
            parameters.put("rhs", 2);
            tree.setAstTransformation(new AstTransformNodeCreator("LessThan", parameters));
            tree.setExprNud(true);
            tree.add(left);
            tree.add(expect(ctx, WdlTerminalIdentifier.TERMINAL_LT));
            modifier = 0;
            tree.setInfix(true);
            tree.add(parse_e_internal(ctx, get_infix_binding_power_e(14) - modifier));
            return tree;
        }
        if (current.getId() == 47) {
            /* $e = $e :lteq $e -> LessThanOrEqual( lhs=$0, rhs=$2 ) */
            ctx.rule = rules.get(99);
            LinkedHashMap<String, Integer> parameters = new LinkedHashMap<String, Integer>();
            parameters.put("lhs", 0);
            parameters.put("rhs", 2);
            tree.setAstTransformation(new AstTransformNodeCreator("LessThanOrEqual", parameters));
            tree.setExprNud(true);
            tree.add(left);
            tree.add(expect(ctx, WdlTerminalIdentifier.TERMINAL_LTEQ));
            modifier = 0;
            tree.setInfix(true);
            tree.add(parse_e_internal(ctx, get_infix_binding_power_e(47) - modifier));
            return tree;
        }
        if (current.getId() == 39) {
            /* $e = $e :gt $e -> GreaterThan( lhs=$0, rhs=$2 ) */
            ctx.rule = rules.get(100);
            LinkedHashMap<String, Integer> parameters = new LinkedHashMap<String, Integer>();
            parameters.put("lhs", 0);
            parameters.put("rhs", 2);
            tree.setAstTransformation(new AstTransformNodeCreator("GreaterThan", parameters));
            tree.setExprNud(true);
            tree.add(left);
            tree.add(expect(ctx, WdlTerminalIdentifier.TERMINAL_GT));
            modifier = 0;
            tree.setInfix(true);
            tree.add(parse_e_internal(ctx, get_infix_binding_power_e(39) - modifier));
            return tree;
        }
        if (current.getId() == 9) {
            /* $e = $e :gteq $e -> GreaterThanOrEqual( lhs=$0, rhs=$2 ) */
            ctx.rule = rules.get(101);
            LinkedHashMap<String, Integer> parameters = new LinkedHashMap<String, Integer>();
            parameters.put("lhs", 0);
            parameters.put("rhs", 2);
            tree.setAstTransformation(new AstTransformNodeCreator("GreaterThanOrEqual", parameters));
            tree.setExprNud(true);
            tree.add(left);
            tree.add(expect(ctx, WdlTerminalIdentifier.TERMINAL_GTEQ));
            modifier = 0;
            tree.setInfix(true);
            tree.add(parse_e_internal(ctx, get_infix_binding_power_e(9) - modifier));
            return tree;
        }
        if (current.getId() == 2) {
            /* $e = $e :plus $e -> Add( lhs=$0, rhs=$2 ) */
            ctx.rule = rules.get(102);
            LinkedHashMap<String, Integer> parameters = new LinkedHashMap<String, Integer>();
            parameters.put("lhs", 0);
            parameters.put("rhs", 2);
            tree.setAstTransformation(new AstTransformNodeCreator("Add", parameters));
            tree.setExprNud(true);
            tree.add(left);
            tree.add(expect(ctx, WdlTerminalIdentifier.TERMINAL_PLUS));
            modifier = 0;
            tree.setInfix(true);
            tree.add(parse_e_internal(ctx, get_infix_binding_power_e(2) - modifier));
            return tree;
        }
        if (current.getId() == 42) {
            /* $e = $e :dash $e -> Subtract( lhs=$0, rhs=$2 ) */
            ctx.rule = rules.get(103);
            LinkedHashMap<String, Integer> parameters = new LinkedHashMap<String, Integer>();
            parameters.put("lhs", 0);
            parameters.put("rhs", 2);
            tree.setAstTransformation(new AstTransformNodeCreator("Subtract", parameters));
            tree.setExprNud(true);
            tree.add(left);
            tree.add(expect(ctx, WdlTerminalIdentifier.TERMINAL_DASH));
            modifier = 0;
            tree.setInfix(true);
            tree.add(parse_e_internal(ctx, get_infix_binding_power_e(42) - modifier));
            return tree;
        }
        if (current.getId() == 25) {
            /* $e = $e :asterisk $e -> Multiply( lhs=$0, rhs=$2 ) */
            ctx.rule = rules.get(104);
            LinkedHashMap<String, Integer> parameters = new LinkedHashMap<String, Integer>();
            parameters.put("lhs", 0);
            parameters.put("rhs", 2);
            tree.setAstTransformation(new AstTransformNodeCreator("Multiply", parameters));
            tree.setExprNud(true);
            tree.add(left);
            tree.add(expect(ctx, WdlTerminalIdentifier.TERMINAL_ASTERISK));
            modifier = 0;
            tree.setInfix(true);
            tree.add(parse_e_internal(ctx, get_infix_binding_power_e(25) - modifier));
            return tree;
        }
        if (current.getId() == 19) {
            /* $e = $e :slash $e -> Divide( lhs=$0, rhs=$2 ) */
            ctx.rule = rules.get(105);
            LinkedHashMap<String, Integer> parameters = new LinkedHashMap<String, Integer>();
            parameters.put("lhs", 0);
            parameters.put("rhs", 2);
            tree.setAstTransformation(new AstTransformNodeCreator("Divide", parameters));
            tree.setExprNud(true);
            tree.add(left);
            tree.add(expect(ctx, WdlTerminalIdentifier.TERMINAL_SLASH));
            modifier = 0;
            tree.setInfix(true);
            tree.add(parse_e_internal(ctx, get_infix_binding_power_e(19) - modifier));
            return tree;
        }
        if (current.getId() == 20) {
            /* $e = $e :percent $e -> Remainder( lhs=$0, rhs=$2 ) */
            ctx.rule = rules.get(106);
            LinkedHashMap<String, Integer> parameters = new LinkedHashMap<String, Integer>();
            parameters.put("lhs", 0);
            parameters.put("rhs", 2);
            tree.setAstTransformation(new AstTransformNodeCreator("Remainder", parameters));
            tree.setExprNud(true);
            tree.add(left);
            tree.add(expect(ctx, WdlTerminalIdentifier.TERMINAL_PERCENT));
            modifier = 0;
            tree.setInfix(true);
            tree.add(parse_e_internal(ctx, get_infix_binding_power_e(20) - modifier));
            return tree;
        }
        if (current.getId() == 8) {
            /* $e = :identifier <=> :lparen $_gen21 :rparen -> FunctionCall( name=$0, params=$2 ) */
            ctx.rule = rules.get(111);
            LinkedHashMap<String, Integer> parameters = new LinkedHashMap<String, Integer>();
            parameters.put("name", 0);
            parameters.put("params", 2);
            tree.setAstTransformation(new AstTransformNodeCreator("FunctionCall", parameters));
            tree.add(left);
            tree.add(expect(ctx, WdlTerminalIdentifier.TERMINAL_LPAREN));
            tree.add(parse__gen21(ctx));
            tree.add(expect(ctx, WdlTerminalIdentifier.TERMINAL_RPAREN));
            return tree;
        }
        if (current.getId() == 7) {
            /* $e = :identifier <=> :lsquare $e :rsquare -> ArrayOrMapLookup( lhs=$0, rhs=$2 ) */
            ctx.rule = rules.get(112);
            LinkedHashMap<String, Integer> parameters = new LinkedHashMap<String, Integer>();
            parameters.put("lhs", 0);
            parameters.put("rhs", 2);
            tree.setAstTransformation(new AstTransformNodeCreator("ArrayOrMapLookup", parameters));
            tree.add(left);
            tree.add(expect(ctx, WdlTerminalIdentifier.TERMINAL_LSQUARE));
            modifier = 0;
            tree.add(parse_e_internal(ctx, get_infix_binding_power_e(7) - modifier));
            tree.add(expect(ctx, WdlTerminalIdentifier.TERMINAL_RSQUARE));
            return tree;
        }
        if (current.getId() == 33) {
            /* $e = :identifier <=> :dot :identifier -> MemberAccess( lhs=$0, rhs=$2 ) */
            ctx.rule = rules.get(113);
            LinkedHashMap<String, Integer> parameters = new LinkedHashMap<String, Integer>();
            parameters.put("lhs", 0);
            parameters.put("rhs", 2);
            tree.setAstTransformation(new AstTransformNodeCreator("MemberAccess", parameters));
            tree.add(left);
            tree.add(expect(ctx, WdlTerminalIdentifier.TERMINAL_DOT));
            tree.add(expect(ctx, WdlTerminalIdentifier.TERMINAL_IDENTIFIER));
            return tree;
        }
        return tree;
    }
    private static Map<Integer, Integer> infix_binding_power_type_e;
    private static Map<Integer, Integer> prefix_binding_power_type_e;
    static {
        Map<Integer, Integer> map = new HashMap<Integer, Integer>();
        map.put(7, 1000); /* $type_e = :type <=> :lsquare list($type_e, :comma) :rsquare -> Type( name=$0, subtype=$2 ) */
        map.put(45, 2000); /* $type_e = :type <=> :qmark -> OptionalType( innerType=$0 ) */
        map.put(2, 3000); /* $type_e = :type <=> :plus -> NonEmptyType( innerType=$0 ) */
        infix_binding_power_type_e = Collections.unmodifiableMap(map);
    }
    static {
        Map<Integer, Integer> map = new HashMap<Integer, Integer>();
        prefix_binding_power_type_e = Collections.unmodifiableMap(map);
    }
    static int get_infix_binding_power_type_e(int terminal_id) {
        if (infix_binding_power_type_e.containsKey(terminal_id)) {
            return infix_binding_power_type_e.get(terminal_id);
        }
        return 0;
    }
    static int get_prefix_binding_power_type_e(int terminal_id) {
        if (prefix_binding_power_type_e.containsKey(terminal_id)) {
            return prefix_binding_power_type_e.get(terminal_id);
        }
        return 0;
    }
    public ParseTree parse_type_e(List<Terminal> tokens, SyntaxErrorFormatter error_formatter) throws SyntaxError {
        ParserContext ctx = new ParserContext(new TokenStream(tokens), error_formatter);
        return parse_type_e_internal(ctx, 0);
    }
    public static ParseTree parse_type_e(ParserContext ctx) throws SyntaxError {
        return parse_type_e_internal(ctx, 0);
    }
    public static ParseTree parse_type_e_internal(ParserContext ctx, int rbp) throws SyntaxError {
        ParseTree left = nud_type_e(ctx);
        if ( left instanceof ParseTree ) {
            left.setExpr(true);
            left.setNud(true);
        }
        while (ctx.tokens.current() != null && rbp < get_infix_binding_power_type_e(ctx.tokens.current().getId())) {
            left = led_type_e(left, ctx);
        }
        if (left != null) {
            left.setExpr(true);
        }
        return left;
    }
    private static ParseTree nud_type_e(ParserContext ctx) throws SyntaxError {
        ParseTree tree = new ParseTree( new NonTerminal(125, "type_e") );
        Terminal current = ctx.tokens.current();
        ctx.nonterminal = "type_e";
        if (current == null) {
            return tree;
        }
        if (rule_first.get(90).contains(terminal_map.get(current.getId()))) {
            /* (90) $type_e = :type <=> :lsquare $_gen20 :rsquare -> Type( name=$0, subtype=$2 ) */
            ctx.rule = rules.get(90);
            tree.setAstTransformation(new AstTransformSubstitution(0));
            tree.setNudMorphemeCount(1);
            tree.add(expect(ctx, WdlTerminalIdentifier.TERMINAL_TYPE));
        }
        else if (rule_first.get(91).contains(terminal_map.get(current.getId()))) {
            /* (91) $type_e = :type <=> :qmark -> OptionalType( innerType=$0 ) */
            ctx.rule = rules.get(91);
            tree.setAstTransformation(new AstTransformSubstitution(0));
            tree.setNudMorphemeCount(1);
            tree.add(expect(ctx, WdlTerminalIdentifier.TERMINAL_TYPE));
        }
        else if (rule_first.get(92).contains(terminal_map.get(current.getId()))) {
            /* (92) $type_e = :type <=> :plus -> NonEmptyType( innerType=$0 ) */
            ctx.rule = rules.get(92);
            tree.setAstTransformation(new AstTransformSubstitution(0));
            tree.setNudMorphemeCount(1);
            tree.add(expect(ctx, WdlTerminalIdentifier.TERMINAL_TYPE));
        }
        else if (rule_first.get(93).contains(terminal_map.get(current.getId()))) {
            /* (93) $type_e = :type */
            ctx.rule = rules.get(93);
            tree.setAstTransformation(new AstTransformSubstitution(0));
            tree.setNudMorphemeCount(1);
            tree.add(expect(ctx, WdlTerminalIdentifier.TERMINAL_TYPE));
        }
        return tree;
    }
    private static ParseTree led_type_e(ParseTree left, ParserContext ctx) throws SyntaxError {
        ParseTree tree = new ParseTree( new NonTerminal(125, "type_e") );
        Terminal current = ctx.tokens.current();
        ctx.nonterminal = "type_e";
        int modifier;
        if (current.getId() == 7) {
            /* $type_e = :type <=> :lsquare $_gen20 :rsquare -> Type( name=$0, subtype=$2 ) */
            ctx.rule = rules.get(90);
            LinkedHashMap<String, Integer> parameters = new LinkedHashMap<String, Integer>();
            parameters.put("name", 0);
            parameters.put("subtype", 2);
            tree.setAstTransformation(new AstTransformNodeCreator("Type", parameters));
            tree.add(left);
            tree.add(expect(ctx, WdlTerminalIdentifier.TERMINAL_LSQUARE));
            tree.add(parse__gen20(ctx));
            tree.add(expect(ctx, WdlTerminalIdentifier.TERMINAL_RSQUARE));
            return tree;
        }
        if (current.getId() == 45) {
            /* $type_e = :type <=> :qmark -> OptionalType( innerType=$0 ) */
            ctx.rule = rules.get(91);
            LinkedHashMap<String, Integer> parameters = new LinkedHashMap<String, Integer>();
            parameters.put("innerType", 0);
            tree.setAstTransformation(new AstTransformNodeCreator("OptionalType", parameters));
            tree.add(left);
            tree.add(expect(ctx, WdlTerminalIdentifier.TERMINAL_QMARK));
            return tree;
        }
        if (current.getId() == 2) {
            /* $type_e = :type <=> :plus -> NonEmptyType( innerType=$0 ) */
            ctx.rule = rules.get(92);
            LinkedHashMap<String, Integer> parameters = new LinkedHashMap<String, Integer>();
            parameters.put("innerType", 0);
            tree.setAstTransformation(new AstTransformNodeCreator("NonEmptyType", parameters));
            tree.add(left);
            tree.add(expect(ctx, WdlTerminalIdentifier.TERMINAL_PLUS));
            return tree;
        }
        return tree;
    }
    public ParseTree parse__gen0(List<Terminal> tokens, SyntaxErrorFormatter error_formatter) throws SyntaxError {
        ParserContext ctx = new ParserContext(new TokenStream(tokens), error_formatter);
        return parse__gen0(ctx);
    }
    private static ParseTree parse__gen0(ParserContext ctx) throws SyntaxError {
        ParseTree tree = new ParseTree(new NonTerminal(113, "_gen0"));
        tree.setList(true);
        ctx.nonterminal = "_gen0";
        if ( ctx.tokens.current() != null &&
             !nonterminal_first.get(113).contains(terminal_map.get(ctx.tokens.current().getId())) &&
              nonterminal_follow.get(113).contains(terminal_map.get(ctx.tokens.current().getId())) ) {
            return tree;
        }
        if (ctx.tokens.current() == null) {
            return tree;
        }
        int minimum = 0;
        while (minimum > 0 ||
               (ctx.tokens.current() != null &&
               nonterminal_first.get(113).contains(terminal_map.get(ctx.tokens.current().getId())))) {
            tree.add(parse_import(ctx));
            ctx.nonterminal = "_gen0"; // because parse_* can reset this
            minimum = Math.max(minimum - 1, 0);
        }
        return tree;
    }
    public ParseTree parse__gen1(List<Terminal> tokens, SyntaxErrorFormatter error_formatter) throws SyntaxError {
        ParserContext ctx = new ParserContext(new TokenStream(tokens), error_formatter);
        return parse__gen1(ctx);
    }
    private static ParseTree parse__gen1(ParserContext ctx) throws SyntaxError {
        ParseTree tree = new ParseTree(new NonTerminal(124, "_gen1"));
        tree.setList(true);
        ctx.nonterminal = "_gen1";
        if ( ctx.tokens.current() != null &&
             !nonterminal_first.get(124).contains(terminal_map.get(ctx.tokens.current().getId())) &&
              nonterminal_follow.get(124).contains(terminal_map.get(ctx.tokens.current().getId())) ) {
            return tree;
        }
        if (ctx.tokens.current() == null) {
            return tree;
        }
        int minimum = 0;
        while (minimum > 0 ||
               (ctx.tokens.current() != null &&
               nonterminal_first.get(124).contains(terminal_map.get(ctx.tokens.current().getId())))) {
            tree.add(parse_workflow_or_task_or_decl(ctx));
            ctx.nonterminal = "_gen1"; // because parse_* can reset this
            minimum = Math.max(minimum - 1, 0);
        }
        return tree;
    }
    public ParseTree parse__gen10(List<Terminal> tokens, SyntaxErrorFormatter error_formatter) throws SyntaxError {
        ParserContext ctx = new ParserContext(new TokenStream(tokens), error_formatter);
        return parse__gen10(ctx);
    }
    private static ParseTree parse__gen10(ParserContext ctx) throws SyntaxError {
        ParseTree tree = new ParseTree(new NonTerminal(67, "_gen10"));
        tree.setList(true);
        tree.setListSeparator(23);
        ctx.nonterminal = "_gen10";
        if ( ctx.tokens.current() != null &&
             !nonterminal_first.get(67).contains(terminal_map.get(ctx.tokens.current().getId())) &&
              nonterminal_follow.get(67).contains(terminal_map.get(ctx.tokens.current().getId())) ) {
            return tree;
        }
        if (ctx.tokens.current() == null) {
            return tree;
        }
        int minimum = 0;
        while (minimum > 0 ||
               (ctx.tokens.current() != null &&
               nonterminal_first.get(67).contains(terminal_map.get(ctx.tokens.current().getId())))) {
            tree.add(parse_meta_kv(ctx));
            ctx.nonterminal = "_gen10"; // because parse_* can reset this
            if (ctx.tokens.current() != null &&
                ctx.tokens.current().getId() == WdlTerminalIdentifier.TERMINAL_COMMA.id()) {
              tree.add(expect(ctx, WdlTerminalIdentifier.TERMINAL_COMMA));
            } else {
              if (minimum > 1) {
                  throw new SyntaxError(ctx.error_formatter.missingListItems(
                      "_gen10",
                      0,
                      0 - minimum + 1,
                      null
                  ));
              }
              break;
            }
            minimum = Math.max(minimum - 1, 0);
        }
        return tree;
    }
    public ParseTree parse__gen11(List<Terminal> tokens, SyntaxErrorFormatter error_formatter) throws SyntaxError {
        ParserContext ctx = new ParserContext(new TokenStream(tokens), error_formatter);
        return parse__gen11(ctx);
    }
    private static ParseTree parse__gen11(ParserContext ctx) throws SyntaxError {
        ParseTree tree = new ParseTree(new NonTerminal(90, "_gen11"));
        tree.setList(true);
        tree.setListSeparator(23);
        ctx.nonterminal = "_gen11";
        if ( ctx.tokens.current() != null &&
             !nonterminal_first.get(90).contains(terminal_map.get(ctx.tokens.current().getId())) &&
              nonterminal_follow.get(90).contains(terminal_map.get(ctx.tokens.current().getId())) ) {
            return tree;
        }
        if (ctx.tokens.current() == null) {
            return tree;
        }
        int minimum = 0;
        while (minimum > 0 ||
               (ctx.tokens.current() != null &&
               nonterminal_first.get(90).contains(terminal_map.get(ctx.tokens.current().getId())))) {
            tree.add(parse_meta_value(ctx));
            ctx.nonterminal = "_gen11"; // because parse_* can reset this
            if (ctx.tokens.current() != null &&
                ctx.tokens.current().getId() == WdlTerminalIdentifier.TERMINAL_COMMA.id()) {
              tree.add(expect(ctx, WdlTerminalIdentifier.TERMINAL_COMMA));
            } else {
              if (minimum > 1) {
                  throw new SyntaxError(ctx.error_formatter.missingListItems(
                      "_gen11",
                      0,
                      0 - minimum + 1,
                      null
                  ));
              }
              break;
            }
            minimum = Math.max(minimum - 1, 0);
        }
        return tree;
    }
    public ParseTree parse__gen13(List<Terminal> tokens, SyntaxErrorFormatter error_formatter) throws SyntaxError {
        ParserContext ctx = new ParserContext(new TokenStream(tokens), error_formatter);
        return parse__gen13(ctx);
    }
    private static ParseTree parse__gen13(ParserContext ctx) throws SyntaxError {
        ParseTree tree = new ParseTree(new NonTerminal(117, "_gen13"));
        tree.setList(true);
        ctx.nonterminal = "_gen13";
        if ( ctx.tokens.current() != null &&
             !nonterminal_first.get(117).contains(terminal_map.get(ctx.tokens.current().getId())) &&
              nonterminal_follow.get(117).contains(terminal_map.get(ctx.tokens.current().getId())) ) {
            return tree;
        }
        if (ctx.tokens.current() == null) {
            return tree;
        }
        int minimum = 0;
        while (minimum > 0 ||
               (ctx.tokens.current() != null &&
               nonterminal_first.get(117).contains(terminal_map.get(ctx.tokens.current().getId())))) {
            tree.add(parse_wf_body_element(ctx));
            ctx.nonterminal = "_gen13"; // because parse_* can reset this
            minimum = Math.max(minimum - 1, 0);
        }
        return tree;
    }
    public ParseTree parse__gen16(List<Terminal> tokens, SyntaxErrorFormatter error_formatter) throws SyntaxError {
        ParserContext ctx = new ParserContext(new TokenStream(tokens), error_formatter);
        return parse__gen16(ctx);
    }
    private static ParseTree parse__gen16(ParserContext ctx) throws SyntaxError {
        ParseTree tree = new ParseTree(new NonTerminal(73, "_gen16"));
        tree.setList(true);
        ctx.nonterminal = "_gen16";
        if ( ctx.tokens.current() != null &&
             !nonterminal_first.get(73).contains(terminal_map.get(ctx.tokens.current().getId())) &&
              nonterminal_follow.get(73).contains(terminal_map.get(ctx.tokens.current().getId())) ) {
            return tree;
        }
        if (ctx.tokens.current() == null) {
            return tree;
        }
        int minimum = 0;
        while (minimum > 0 ||
               (ctx.tokens.current() != null &&
               nonterminal_first.get(73).contains(terminal_map.get(ctx.tokens.current().getId())))) {
            tree.add(parse_call_input(ctx));
            ctx.nonterminal = "_gen16"; // because parse_* can reset this
            minimum = Math.max(minimum - 1, 0);
        }
        return tree;
    }
    public ParseTree parse__gen17(List<Terminal> tokens, SyntaxErrorFormatter error_formatter) throws SyntaxError {
        ParserContext ctx = new ParserContext(new TokenStream(tokens), error_formatter);
        return parse__gen17(ctx);
    }
    private static ParseTree parse__gen17(ParserContext ctx) throws SyntaxError {
        ParseTree tree = new ParseTree(new NonTerminal(88, "_gen17"));
        tree.setList(true);
        tree.setListSeparator(23);
        ctx.nonterminal = "_gen17";
        if ( ctx.tokens.current() != null &&
             !nonterminal_first.get(88).contains(terminal_map.get(ctx.tokens.current().getId())) &&
              nonterminal_follow.get(88).contains(terminal_map.get(ctx.tokens.current().getId())) ) {
            return tree;
        }
        if (ctx.tokens.current() == null) {
            return tree;
        }
        int minimum = 0;
        while (minimum > 0 ||
               (ctx.tokens.current() != null &&
               nonterminal_first.get(88).contains(terminal_map.get(ctx.tokens.current().getId())))) {
            tree.add(parse_mapping(ctx));
            ctx.nonterminal = "_gen17"; // because parse_* can reset this
            if (ctx.tokens.current() != null &&
                ctx.tokens.current().getId() == WdlTerminalIdentifier.TERMINAL_COMMA.id()) {
              tree.add(expect(ctx, WdlTerminalIdentifier.TERMINAL_COMMA));
            } else {
              if (minimum > 1) {
                  throw new SyntaxError(ctx.error_formatter.missingListItems(
                      "_gen17",
                      0,
                      0 - minimum + 1,
                      null
                  ));
              }
              break;
            }
            minimum = Math.max(minimum - 1, 0);
        }
        return tree;
    }
    public ParseTree parse__gen18(List<Terminal> tokens, SyntaxErrorFormatter error_formatter) throws SyntaxError {
        ParserContext ctx = new ParserContext(new TokenStream(tokens), error_formatter);
        return parse__gen18(ctx);
    }
    private static ParseTree parse__gen18(ParserContext ctx) throws SyntaxError {
        ParseTree tree = new ParseTree(new NonTerminal(100, "_gen18"));
        tree.setList(true);
        ctx.nonterminal = "_gen18";
        if ( ctx.tokens.current() != null &&
             !nonterminal_first.get(100).contains(terminal_map.get(ctx.tokens.current().getId())) &&
              nonterminal_follow.get(100).contains(terminal_map.get(ctx.tokens.current().getId())) ) {
            return tree;
        }
        if (ctx.tokens.current() == null) {
            return tree;
        }
        int minimum = 0;
        while (minimum > 0 ||
               (ctx.tokens.current() != null &&
               nonterminal_first.get(100).contains(terminal_map.get(ctx.tokens.current().getId())))) {
            tree.add(parse_wf_output(ctx));
            ctx.nonterminal = "_gen18"; // because parse_* can reset this
            minimum = Math.max(minimum - 1, 0);
        }
        return tree;
    }
    public ParseTree parse__gen20(List<Terminal> tokens, SyntaxErrorFormatter error_formatter) throws SyntaxError {
        ParserContext ctx = new ParserContext(new TokenStream(tokens), error_formatter);
        return parse__gen20(ctx);
    }
    private static ParseTree parse__gen20(ParserContext ctx) throws SyntaxError {
        ParseTree tree = new ParseTree(new NonTerminal(64, "_gen20"));
        tree.setList(true);
        tree.setListSeparator(23);
        ctx.nonterminal = "_gen20";
        if ( ctx.tokens.current() != null &&
             !nonterminal_first.get(64).contains(terminal_map.get(ctx.tokens.current().getId())) &&
              nonterminal_follow.get(64).contains(terminal_map.get(ctx.tokens.current().getId())) ) {
            return tree;
        }
        if (ctx.tokens.current() == null) {
            return tree;
        }
        int minimum = 0;
        while (minimum > 0 ||
               (ctx.tokens.current() != null &&
               nonterminal_first.get(64).contains(terminal_map.get(ctx.tokens.current().getId())))) {
            tree.add(parse_type_e(ctx));
            ctx.nonterminal = "_gen20"; // because parse_* can reset this
            if (ctx.tokens.current() != null &&
                ctx.tokens.current().getId() == WdlTerminalIdentifier.TERMINAL_COMMA.id()) {
              tree.add(expect(ctx, WdlTerminalIdentifier.TERMINAL_COMMA));
            } else {
              if (minimum > 1) {
                  throw new SyntaxError(ctx.error_formatter.missingListItems(
                      "_gen20",
                      0,
                      0 - minimum + 1,
                      null
                  ));
              }
              break;
            }
            minimum = Math.max(minimum - 1, 0);
        }
        return tree;
    }
    public ParseTree parse__gen21(List<Terminal> tokens, SyntaxErrorFormatter error_formatter) throws SyntaxError {
        ParserContext ctx = new ParserContext(new TokenStream(tokens), error_formatter);
        return parse__gen21(ctx);
    }
    private static ParseTree parse__gen21(ParserContext ctx) throws SyntaxError {
        ParseTree tree = new ParseTree(new NonTerminal(126, "_gen21"));
        tree.setList(true);
        tree.setListSeparator(23);
        ctx.nonterminal = "_gen21";
        if ( ctx.tokens.current() != null &&
             !nonterminal_first.get(126).contains(terminal_map.get(ctx.tokens.current().getId())) &&
              nonterminal_follow.get(126).contains(terminal_map.get(ctx.tokens.current().getId())) ) {
            return tree;
        }
        if (ctx.tokens.current() == null) {
            return tree;
        }
        int minimum = 0;
        while (minimum > 0 ||
               (ctx.tokens.current() != null &&
               nonterminal_first.get(126).contains(terminal_map.get(ctx.tokens.current().getId())))) {
            tree.add(parse_e(ctx));
            ctx.nonterminal = "_gen21"; // because parse_* can reset this
            if (ctx.tokens.current() != null &&
                ctx.tokens.current().getId() == WdlTerminalIdentifier.TERMINAL_COMMA.id()) {
              tree.add(expect(ctx, WdlTerminalIdentifier.TERMINAL_COMMA));
            } else {
              if (minimum > 1) {
                  throw new SyntaxError(ctx.error_formatter.missingListItems(
                      "_gen21",
                      0,
                      0 - minimum + 1,
                      null
                  ));
              }
              break;
            }
            minimum = Math.max(minimum - 1, 0);
        }
        return tree;
    }
    public ParseTree parse__gen22(List<Terminal> tokens, SyntaxErrorFormatter error_formatter) throws SyntaxError {
        ParserContext ctx = new ParserContext(new TokenStream(tokens), error_formatter);
        return parse__gen22(ctx);
    }
    private static ParseTree parse__gen22(ParserContext ctx) throws SyntaxError {
        ParseTree tree = new ParseTree(new NonTerminal(68, "_gen22"));
        tree.setList(true);
        tree.setListSeparator(23);
        ctx.nonterminal = "_gen22";
        if ( ctx.tokens.current() != null &&
             !nonterminal_first.get(68).contains(terminal_map.get(ctx.tokens.current().getId())) &&
              nonterminal_follow.get(68).contains(terminal_map.get(ctx.tokens.current().getId())) ) {
            return tree;
        }
        if (ctx.tokens.current() == null) {
            return tree;
        }
        int minimum = 0;
        while (minimum > 0 ||
               (ctx.tokens.current() != null &&
               nonterminal_first.get(68).contains(terminal_map.get(ctx.tokens.current().getId())))) {
            tree.add(parse_object_kv(ctx));
            ctx.nonterminal = "_gen22"; // because parse_* can reset this
            if (ctx.tokens.current() != null &&
                ctx.tokens.current().getId() == WdlTerminalIdentifier.TERMINAL_COMMA.id()) {
              tree.add(expect(ctx, WdlTerminalIdentifier.TERMINAL_COMMA));
            } else {
              if (minimum > 1) {
                  throw new SyntaxError(ctx.error_formatter.missingListItems(
                      "_gen22",
                      0,
                      0 - minimum + 1,
                      null
                  ));
              }
              break;
            }
            minimum = Math.max(minimum - 1, 0);
        }
        return tree;
    }
    public ParseTree parse__gen23(List<Terminal> tokens, SyntaxErrorFormatter error_formatter) throws SyntaxError {
        ParserContext ctx = new ParserContext(new TokenStream(tokens), error_formatter);
        return parse__gen23(ctx);
    }
    private static ParseTree parse__gen23(ParserContext ctx) throws SyntaxError {
        ParseTree tree = new ParseTree(new NonTerminal(70, "_gen23"));
        tree.setList(true);
        tree.setListSeparator(23);
        ctx.nonterminal = "_gen23";
        if ( ctx.tokens.current() != null &&
             !nonterminal_first.get(70).contains(terminal_map.get(ctx.tokens.current().getId())) &&
              nonterminal_follow.get(70).contains(terminal_map.get(ctx.tokens.current().getId())) ) {
            return tree;
        }
        if (ctx.tokens.current() == null) {
            return tree;
        }
        int minimum = 0;
        while (minimum > 0 ||
               (ctx.tokens.current() != null &&
               nonterminal_first.get(70).contains(terminal_map.get(ctx.tokens.current().getId())))) {
            tree.add(parse_map_kv(ctx));
            ctx.nonterminal = "_gen23"; // because parse_* can reset this
            if (ctx.tokens.current() != null &&
                ctx.tokens.current().getId() == WdlTerminalIdentifier.TERMINAL_COMMA.id()) {
              tree.add(expect(ctx, WdlTerminalIdentifier.TERMINAL_COMMA));
            } else {
              if (minimum > 1) {
                  throw new SyntaxError(ctx.error_formatter.missingListItems(
                      "_gen23",
                      0,
                      0 - minimum + 1,
                      null
                  ));
              }
              break;
            }
            minimum = Math.max(minimum - 1, 0);
        }
        return tree;
    }
    public ParseTree parse__gen3(List<Terminal> tokens, SyntaxErrorFormatter error_formatter) throws SyntaxError {
        ParserContext ctx = new ParserContext(new TokenStream(tokens), error_formatter);
        return parse__gen3(ctx);
    }
    private static ParseTree parse__gen3(ParserContext ctx) throws SyntaxError {
        ParseTree tree = new ParseTree(new NonTerminal(85, "_gen3"));
        tree.setList(true);
        ctx.nonterminal = "_gen3";
        if ( ctx.tokens.current() != null &&
             !nonterminal_first.get(85).contains(terminal_map.get(ctx.tokens.current().getId())) &&
              nonterminal_follow.get(85).contains(terminal_map.get(ctx.tokens.current().getId())) ) {
            return tree;
        }
        if (ctx.tokens.current() == null) {
            return tree;
        }
        int minimum = 0;
        while (minimum > 0 ||
               (ctx.tokens.current() != null &&
               nonterminal_first.get(85).contains(terminal_map.get(ctx.tokens.current().getId())))) {
            tree.add(parse_declaration(ctx));
            ctx.nonterminal = "_gen3"; // because parse_* can reset this
            minimum = Math.max(minimum - 1, 0);
        }
        return tree;
    }
    public ParseTree parse__gen4(List<Terminal> tokens, SyntaxErrorFormatter error_formatter) throws SyntaxError {
        ParserContext ctx = new ParserContext(new TokenStream(tokens), error_formatter);
        return parse__gen4(ctx);
    }
    private static ParseTree parse__gen4(ParserContext ctx) throws SyntaxError {
        ParseTree tree = new ParseTree(new NonTerminal(78, "_gen4"));
        tree.setList(true);
        ctx.nonterminal = "_gen4";
        if ( ctx.tokens.current() != null &&
             !nonterminal_first.get(78).contains(terminal_map.get(ctx.tokens.current().getId())) &&
              nonterminal_follow.get(78).contains(terminal_map.get(ctx.tokens.current().getId())) ) {
            return tree;
        }
        if (ctx.tokens.current() == null) {
            return tree;
        }
        int minimum = 0;
        while (minimum > 0 ||
               (ctx.tokens.current() != null &&
               nonterminal_first.get(78).contains(terminal_map.get(ctx.tokens.current().getId())))) {
            tree.add(parse_sections(ctx));
            ctx.nonterminal = "_gen4"; // because parse_* can reset this
            minimum = Math.max(minimum - 1, 0);
        }
        return tree;
    }
    public ParseTree parse__gen5(List<Terminal> tokens, SyntaxErrorFormatter error_formatter) throws SyntaxError {
        ParserContext ctx = new ParserContext(new TokenStream(tokens), error_formatter);
        return parse__gen5(ctx);
    }
    private static ParseTree parse__gen5(ParserContext ctx) throws SyntaxError {
        ParseTree tree = new ParseTree(new NonTerminal(121, "_gen5"));
        tree.setList(true);
        ctx.nonterminal = "_gen5";
        if ( ctx.tokens.current() != null &&
             !nonterminal_first.get(121).contains(terminal_map.get(ctx.tokens.current().getId())) &&
              nonterminal_follow.get(121).contains(terminal_map.get(ctx.tokens.current().getId())) ) {
            return tree;
        }
        if (ctx.tokens.current() == null) {
            return tree;
        }
        int minimum = 0;
        while (minimum > 0 ||
               (ctx.tokens.current() != null &&
               nonterminal_first.get(121).contains(terminal_map.get(ctx.tokens.current().getId())))) {
            tree.add(parse_command_part(ctx));
            ctx.nonterminal = "_gen5"; // because parse_* can reset this
            minimum = Math.max(minimum - 1, 0);
        }
        return tree;
    }
    public ParseTree parse__gen6(List<Terminal> tokens, SyntaxErrorFormatter error_formatter) throws SyntaxError {
        ParserContext ctx = new ParserContext(new TokenStream(tokens), error_formatter);
        return parse__gen6(ctx);
    }
    private static ParseTree parse__gen6(ParserContext ctx) throws SyntaxError {
        ParseTree tree = new ParseTree(new NonTerminal(69, "_gen6"));
        tree.setList(true);
        ctx.nonterminal = "_gen6";
        if ( ctx.tokens.current() != null &&
             !nonterminal_first.get(69).contains(terminal_map.get(ctx.tokens.current().getId())) &&
              nonterminal_follow.get(69).contains(terminal_map.get(ctx.tokens.current().getId())) ) {
            return tree;
        }
        if (ctx.tokens.current() == null) {
            return tree;
        }
        int minimum = 0;
        while (minimum > 0 ||
               (ctx.tokens.current() != null &&
               nonterminal_first.get(69).contains(terminal_map.get(ctx.tokens.current().getId())))) {
            tree.add(parse_cmd_param_kv(ctx));
            ctx.nonterminal = "_gen6"; // because parse_* can reset this
            minimum = Math.max(minimum - 1, 0);
        }
        return tree;
    }
    public ParseTree parse__gen7(List<Terminal> tokens, SyntaxErrorFormatter error_formatter) throws SyntaxError {
        ParserContext ctx = new ParserContext(new TokenStream(tokens), error_formatter);
        return parse__gen7(ctx);
    }
    private static ParseTree parse__gen7(ParserContext ctx) throws SyntaxError {
        ParseTree tree = new ParseTree(new NonTerminal(101, "_gen7"));
        tree.setList(true);
        ctx.nonterminal = "_gen7";
        if ( ctx.tokens.current() != null &&
             !nonterminal_first.get(101).contains(terminal_map.get(ctx.tokens.current().getId())) &&
              nonterminal_follow.get(101).contains(terminal_map.get(ctx.tokens.current().getId())) ) {
            return tree;
        }
        if (ctx.tokens.current() == null) {
            return tree;
        }
        int minimum = 0;
        while (minimum > 0 ||
               (ctx.tokens.current() != null &&
               nonterminal_first.get(101).contains(terminal_map.get(ctx.tokens.current().getId())))) {
            tree.add(parse_output_kv(ctx));
            ctx.nonterminal = "_gen7"; // because parse_* can reset this
            minimum = Math.max(minimum - 1, 0);
        }
        return tree;
    }
    public ParseTree parse__gen8(List<Terminal> tokens, SyntaxErrorFormatter error_formatter) throws SyntaxError {
        ParserContext ctx = new ParserContext(new TokenStream(tokens), error_formatter);
        return parse__gen8(ctx);
    }
    private static ParseTree parse__gen8(ParserContext ctx) throws SyntaxError {
        ParseTree tree = new ParseTree(new NonTerminal(63, "_gen8"));
        tree.setList(true);
        ctx.nonterminal = "_gen8";
        if ( ctx.tokens.current() != null &&
             !nonterminal_first.get(63).contains(terminal_map.get(ctx.tokens.current().getId())) &&
              nonterminal_follow.get(63).contains(terminal_map.get(ctx.tokens.current().getId())) ) {
            return tree;
        }
        if (ctx.tokens.current() == null) {
            return tree;
        }
        int minimum = 0;
        while (minimum > 0 ||
               (ctx.tokens.current() != null &&
               nonterminal_first.get(63).contains(terminal_map.get(ctx.tokens.current().getId())))) {
            tree.add(parse_kv(ctx));
            ctx.nonterminal = "_gen8"; // because parse_* can reset this
            minimum = Math.max(minimum - 1, 0);
        }
        return tree;
    }
    public ParseTree parse__gen9(List<Terminal> tokens, SyntaxErrorFormatter error_formatter) throws SyntaxError {
        ParserContext ctx = new ParserContext(new TokenStream(tokens), error_formatter);
        return parse__gen9(ctx);
    }
    private static ParseTree parse__gen9(ParserContext ctx) throws SyntaxError {
        ParseTree tree = new ParseTree(new NonTerminal(93, "_gen9"));
        tree.setList(true);
        ctx.nonterminal = "_gen9";
        if ( ctx.tokens.current() != null &&
             !nonterminal_first.get(93).contains(terminal_map.get(ctx.tokens.current().getId())) &&
              nonterminal_follow.get(93).contains(terminal_map.get(ctx.tokens.current().getId())) ) {
            return tree;
        }
        if (ctx.tokens.current() == null) {
            return tree;
        }
        int minimum = 0;
        while (minimum > 0 ||
               (ctx.tokens.current() != null &&
               nonterminal_first.get(93).contains(terminal_map.get(ctx.tokens.current().getId())))) {
            tree.add(parse_meta_kv(ctx));
            ctx.nonterminal = "_gen9"; // because parse_* can reset this
            minimum = Math.max(minimum - 1, 0);
        }
        return tree;
    }
    public ParseTree parse__gen12(List<Terminal> tokens, SyntaxErrorFormatter error_formatter) throws SyntaxError {
        ParserContext ctx = new ParserContext(new TokenStream(tokens), error_formatter);
        return parse__gen12(ctx);
    }
    private static ParseTree parse__gen12(ParserContext ctx) throws SyntaxError {
        Terminal current = ctx.tokens.current();
        Terminal next;
        ParseTree subtree;
        int rule = (current != null) ? table[40][current.getId()] : -1;
        ParseTree tree = new ParseTree(new NonTerminal(99, "_gen12"));
        ctx.nonterminal = "_gen12";
        if ( current != null &&
             !nonterminal_first.get(99).contains(terminal_map.get(current.getId())) &&
              nonterminal_follow.get(99).contains(terminal_map.get(current.getId())) ) {
            return tree;
        }
        if (current == null) {
            return tree;
        }
        if (rule == 48) {
            /* $_gen12 = $setter */
            ctx.rule = rules.get(48);
            tree.setAstTransformation(new AstTransformSubstitution(0));
            subtree = parse_setter(ctx);
            tree.add(subtree);
            return tree;
        }
        return tree;
    }
    public ParseTree parse__gen14(List<Terminal> tokens, SyntaxErrorFormatter error_formatter) throws SyntaxError {
        ParserContext ctx = new ParserContext(new TokenStream(tokens), error_formatter);
        return parse__gen14(ctx);
    }
    private static ParseTree parse__gen14(ParserContext ctx) throws SyntaxError {
        Terminal current = ctx.tokens.current();
        Terminal next;
        ParseTree subtree;
        int rule = (current != null) ? table[48][current.getId()] : -1;
        ParseTree tree = new ParseTree(new NonTerminal(107, "_gen14"));
        ctx.nonterminal = "_gen14";
        if ( current != null &&
             !nonterminal_first.get(107).contains(terminal_map.get(current.getId())) &&
              nonterminal_follow.get(107).contains(terminal_map.get(current.getId())) ) {
            return tree;
        }
        if (current == null) {
            return tree;
        }
        if (rule == 63) {
            /* $_gen14 = $alias */
            ctx.rule = rules.get(63);
            tree.setAstTransformation(new AstTransformSubstitution(0));
            subtree = parse_alias(ctx);
            tree.add(subtree);
            return tree;
        }
        return tree;
    }
    public ParseTree parse__gen15(List<Terminal> tokens, SyntaxErrorFormatter error_formatter) throws SyntaxError {
        ParserContext ctx = new ParserContext(new TokenStream(tokens), error_formatter);
        return parse__gen15(ctx);
    }
    private static ParseTree parse__gen15(ParserContext ctx) throws SyntaxError {
        Terminal current = ctx.tokens.current();
        Terminal next;
        ParseTree subtree;
        int rule = (current != null) ? table[44][current.getId()] : -1;
        ParseTree tree = new ParseTree(new NonTerminal(103, "_gen15"));
        ctx.nonterminal = "_gen15";
        if ( current != null &&
             !nonterminal_first.get(103).contains(terminal_map.get(current.getId())) &&
              nonterminal_follow.get(103).contains(terminal_map.get(current.getId())) ) {
            return tree;
        }
        if (current == null) {
            return tree;
        }
        if (rule == 65) {
            /* $_gen15 = $call_body */
            ctx.rule = rules.get(65);
            tree.setAstTransformation(new AstTransformSubstitution(0));
            subtree = parse_call_body(ctx);
            tree.add(subtree);
            return tree;
        }
        return tree;
    }
    public ParseTree parse__gen19(List<Terminal> tokens, SyntaxErrorFormatter error_formatter) throws SyntaxError {
        ParserContext ctx = new ParserContext(new TokenStream(tokens), error_formatter);
        return parse__gen19(ctx);
    }
    private static ParseTree parse__gen19(ParserContext ctx) throws SyntaxError {
        Terminal current = ctx.tokens.current();
        Terminal next;
        ParseTree subtree;
        int rule = (current != null) ? table[53][current.getId()] : -1;
        ParseTree tree = new ParseTree(new NonTerminal(112, "_gen19"));
        ctx.nonterminal = "_gen19";
        if ( current != null &&
             !nonterminal_first.get(112).contains(terminal_map.get(current.getId())) &&
              nonterminal_follow.get(112).contains(terminal_map.get(current.getId())) ) {
            return tree;
        }
        if (current == null) {
            return tree;
        }
        if (rule == 79) {
            /* $_gen19 = $wf_output_wildcard */
            ctx.rule = rules.get(79);
            tree.setAstTransformation(new AstTransformSubstitution(0));
            subtree = parse_wf_output_wildcard(ctx);
            tree.add(subtree);
            return tree;
        }
        return tree;
    }
    public ParseTree parse__gen2(List<Terminal> tokens, SyntaxErrorFormatter error_formatter) throws SyntaxError {
        ParserContext ctx = new ParserContext(new TokenStream(tokens), error_formatter);
        return parse__gen2(ctx);
    }
    private static ParseTree parse__gen2(ParserContext ctx) throws SyntaxError {
        Terminal current = ctx.tokens.current();
        Terminal next;
        ParseTree subtree;
        int rule = (current != null) ? table[24][current.getId()] : -1;
        ParseTree tree = new ParseTree(new NonTerminal(83, "_gen2"));
        ctx.nonterminal = "_gen2";
        if ( current != null &&
             !nonterminal_first.get(83).contains(terminal_map.get(current.getId())) &&
              nonterminal_follow.get(83).contains(terminal_map.get(current.getId())) ) {
            return tree;
        }
        if (current == null) {
            return tree;
        }
        if (rule == 6) {
            /* $_gen2 = $import_namespace */
            ctx.rule = rules.get(6);
            tree.setAstTransformation(new AstTransformSubstitution(0));
            subtree = parse_import_namespace(ctx);
            tree.add(subtree);
            return tree;
        }
        return tree;
    }
    public ParseTree parse_alias(List<Terminal> tokens, SyntaxErrorFormatter error_formatter) throws SyntaxError {
        ParserContext ctx = new ParserContext(new TokenStream(tokens), error_formatter);
        return parse_alias(ctx);
    }
    private static ParseTree parse_alias(ParserContext ctx) throws SyntaxError {
        Terminal current = ctx.tokens.current();
        Terminal next;
        ParseTree subtree;
        int rule = (current != null) ? table[68][current.getId()] : -1;
        ParseTree tree = new ParseTree(new NonTerminal(127, "alias"));
        ctx.nonterminal = "alias";
        if (current == null) {
            throw new SyntaxError(ctx.error_formatter.unexpectedEof(
                "alias",
                nonterminal_first.get(127),
                nonterminal_rules.get(127)
            ));
        }
        if (rule == 73) {
            /* $alias = :as :identifier -> $1 */
            ctx.rule = rules.get(73);
            tree.setAstTransformation(new AstTransformSubstitution(1));
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_AS);
            tree.add(next);
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_IDENTIFIER);
            tree.add(next);
            return tree;
        }
        throw new SyntaxError(ctx.error_formatter.unexpectedSymbol(
            "alias",
            current,
            nonterminal_first.get(127),
            rules.get(73)
        ));
    }
    public ParseTree parse_call(List<Terminal> tokens, SyntaxErrorFormatter error_formatter) throws SyntaxError {
        ParserContext ctx = new ParserContext(new TokenStream(tokens), error_formatter);
        return parse_call(ctx);
    }
    private static ParseTree parse_call(ParserContext ctx) throws SyntaxError {
        Terminal current = ctx.tokens.current();
        Terminal next;
        ParseTree subtree;
        int rule = (current != null) ? table[12][current.getId()] : -1;
        ParseTree tree = new ParseTree(new NonTerminal(71, "call"));
        ctx.nonterminal = "call";
        if (current == null) {
            throw new SyntaxError(ctx.error_formatter.unexpectedEof(
                "call",
                nonterminal_first.get(71),
                nonterminal_rules.get(71)
            ));
        }
        if (rule == 67) {
            /* $call = :call :fqn $_gen14 $_gen15 -> Call( task=$1, alias=$2, body=$3 ) */
            ctx.rule = rules.get(67);
            LinkedHashMap<String, Integer> parameters = new LinkedHashMap<String, Integer>();
            parameters.put("task", 1);
            parameters.put("alias", 2);
            parameters.put("body", 3);
            tree.setAstTransformation(new AstTransformNodeCreator("Call", parameters));
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_CALL);
            tree.add(next);
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_FQN);
            tree.add(next);
            subtree = parse__gen14(ctx);
            tree.add(subtree);
            subtree = parse__gen15(ctx);
            tree.add(subtree);
            return tree;
        }
        throw new SyntaxError(ctx.error_formatter.unexpectedSymbol(
            "call",
            current,
            nonterminal_first.get(71),
            rules.get(67)
        ));
    }
    public ParseTree parse_call_body(List<Terminal> tokens, SyntaxErrorFormatter error_formatter) throws SyntaxError {
        ParserContext ctx = new ParserContext(new TokenStream(tokens), error_formatter);
        return parse_call_body(ctx);
    }
    private static ParseTree parse_call_body(ParserContext ctx) throws SyntaxError {
        Terminal current = ctx.tokens.current();
        Terminal next;
        ParseTree subtree;
        int rule = (current != null) ? table[43][current.getId()] : -1;
        ParseTree tree = new ParseTree(new NonTerminal(102, "call_body"));
        ctx.nonterminal = "call_body";
        if (current == null) {
            throw new SyntaxError(ctx.error_formatter.unexpectedEof(
                "call_body",
                nonterminal_first.get(102),
                nonterminal_rules.get(102)
            ));
        }
        if (rule == 69) {
            /* $call_body = :lbrace $_gen3 $_gen16 :rbrace -> CallBody( declarations=$1, io=$2 ) */
            ctx.rule = rules.get(69);
            LinkedHashMap<String, Integer> parameters = new LinkedHashMap<String, Integer>();
            parameters.put("declarations", 1);
            parameters.put("io", 2);
            tree.setAstTransformation(new AstTransformNodeCreator("CallBody", parameters));
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_LBRACE);
            tree.add(next);
            subtree = parse__gen3(ctx);
            tree.add(subtree);
            subtree = parse__gen16(ctx);
            tree.add(subtree);
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_RBRACE);
            tree.add(next);
            return tree;
        }
        throw new SyntaxError(ctx.error_formatter.unexpectedSymbol(
            "call_body",
            current,
            nonterminal_first.get(102),
            rules.get(69)
        ));
    }
    public ParseTree parse_call_input(List<Terminal> tokens, SyntaxErrorFormatter error_formatter) throws SyntaxError {
        ParserContext ctx = new ParserContext(new TokenStream(tokens), error_formatter);
        return parse_call_input(ctx);
    }
    private static ParseTree parse_call_input(ParserContext ctx) throws SyntaxError {
        Terminal current = ctx.tokens.current();
        Terminal next;
        ParseTree subtree;
        int rule = (current != null) ? table[16][current.getId()] : -1;
        ParseTree tree = new ParseTree(new NonTerminal(75, "call_input"));
        ctx.nonterminal = "call_input";
        if (current == null) {
            throw new SyntaxError(ctx.error_formatter.unexpectedEof(
                "call_input",
                nonterminal_first.get(75),
                nonterminal_rules.get(75)
            ));
        }
        if (rule == 71) {
            /* $call_input = :input :colon $_gen17 -> Inputs( map=$2 ) */
            ctx.rule = rules.get(71);
            LinkedHashMap<String, Integer> parameters = new LinkedHashMap<String, Integer>();
            parameters.put("map", 2);
            tree.setAstTransformation(new AstTransformNodeCreator("Inputs", parameters));
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_INPUT);
            tree.add(next);
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_COLON);
            tree.add(next);
            subtree = parse__gen17(ctx);
            tree.add(subtree);
            return tree;
        }
        throw new SyntaxError(ctx.error_formatter.unexpectedSymbol(
            "call_input",
            current,
            nonterminal_first.get(75),
            rules.get(71)
        ));
    }
    public ParseTree parse_cmd_param(List<Terminal> tokens, SyntaxErrorFormatter error_formatter) throws SyntaxError {
        ParserContext ctx = new ParserContext(new TokenStream(tokens), error_formatter);
        return parse_cmd_param(ctx);
    }
    private static ParseTree parse_cmd_param(ParserContext ctx) throws SyntaxError {
        Terminal current = ctx.tokens.current();
        Terminal next;
        ParseTree subtree;
        int rule = (current != null) ? table[49][current.getId()] : -1;
        ParseTree tree = new ParseTree(new NonTerminal(108, "cmd_param"));
        ctx.nonterminal = "cmd_param";
        if (current == null) {
            throw new SyntaxError(ctx.error_formatter.unexpectedEof(
                "cmd_param",
                nonterminal_first.get(108),
                nonterminal_rules.get(108)
            ));
        }
        if (rule == 23) {
            /* $cmd_param = :cmd_param_start $_gen6 $e :cmd_param_end -> CommandParameter( attributes=$1, expr=$2 ) */
            ctx.rule = rules.get(23);
            LinkedHashMap<String, Integer> parameters = new LinkedHashMap<String, Integer>();
            parameters.put("attributes", 1);
            parameters.put("expr", 2);
            tree.setAstTransformation(new AstTransformNodeCreator("CommandParameter", parameters));
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_CMD_PARAM_START);
            tree.add(next);
            subtree = parse__gen6(ctx);
            tree.add(subtree);
            subtree = parse_e(ctx);
            tree.add(subtree);
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_CMD_PARAM_END);
            tree.add(next);
            return tree;
        }
        throw new SyntaxError(ctx.error_formatter.unexpectedSymbol(
            "cmd_param",
            current,
            nonterminal_first.get(108),
            rules.get(23)
        ));
    }
    public ParseTree parse_cmd_param_kv(List<Terminal> tokens, SyntaxErrorFormatter error_formatter) throws SyntaxError {
        ParserContext ctx = new ParserContext(new TokenStream(tokens), error_formatter);
        return parse_cmd_param_kv(ctx);
    }
    private static ParseTree parse_cmd_param_kv(ParserContext ctx) throws SyntaxError {
        Terminal current = ctx.tokens.current();
        Terminal next;
        ParseTree subtree;
        int rule = (current != null) ? table[0][current.getId()] : -1;
        ParseTree tree = new ParseTree(new NonTerminal(59, "cmd_param_kv"));
        ctx.nonterminal = "cmd_param_kv";
        if (current == null) {
            throw new SyntaxError(ctx.error_formatter.unexpectedEof(
                "cmd_param_kv",
                nonterminal_first.get(59),
                nonterminal_rules.get(59)
            ));
        }
        if (rule == 24) {
            /* $cmd_param_kv = :cmd_attr_hint :identifier :equal $e -> CommandParameterAttr( key=$1, value=$3 ) */
            ctx.rule = rules.get(24);
            LinkedHashMap<String, Integer> parameters = new LinkedHashMap<String, Integer>();
            parameters.put("key", 1);
            parameters.put("value", 3);
            tree.setAstTransformation(new AstTransformNodeCreator("CommandParameterAttr", parameters));
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_CMD_ATTR_HINT);
            tree.add(next);
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_IDENTIFIER);
            tree.add(next);
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_EQUAL);
            tree.add(next);
            subtree = parse_e(ctx);
            tree.add(subtree);
            return tree;
        }
        throw new SyntaxError(ctx.error_formatter.unexpectedSymbol(
            "cmd_param_kv",
            current,
            nonterminal_first.get(59),
            rules.get(24)
        ));
    }
    public ParseTree parse_command(List<Terminal> tokens, SyntaxErrorFormatter error_formatter) throws SyntaxError {
        ParserContext ctx = new ParserContext(new TokenStream(tokens), error_formatter);
        return parse_command(ctx);
    }
    private static ParseTree parse_command(ParserContext ctx) throws SyntaxError {
        Terminal current = ctx.tokens.current();
        Terminal next;
        ParseTree subtree;
        int rule = (current != null) ? table[46][current.getId()] : -1;
        ParseTree tree = new ParseTree(new NonTerminal(105, "command"));
        ctx.nonterminal = "command";
        if (current == null) {
            throw new SyntaxError(ctx.error_formatter.unexpectedEof(
                "command",
                nonterminal_first.get(105),
                nonterminal_rules.get(105)
            ));
        }
        if (rule == 19) {
            /* $command = :raw_command :raw_cmd_start $_gen5 :raw_cmd_end -> RawCommand( parts=$2 ) */
            ctx.rule = rules.get(19);
            LinkedHashMap<String, Integer> parameters = new LinkedHashMap<String, Integer>();
            parameters.put("parts", 2);
            tree.setAstTransformation(new AstTransformNodeCreator("RawCommand", parameters));
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_RAW_COMMAND);
            tree.add(next);
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_RAW_CMD_START);
            tree.add(next);
            subtree = parse__gen5(ctx);
            tree.add(subtree);
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_RAW_CMD_END);
            tree.add(next);
            return tree;
        }
        throw new SyntaxError(ctx.error_formatter.unexpectedSymbol(
            "command",
            current,
            nonterminal_first.get(105),
            rules.get(19)
        ));
    }
    public ParseTree parse_command_part(List<Terminal> tokens, SyntaxErrorFormatter error_formatter) throws SyntaxError {
        ParserContext ctx = new ParserContext(new TokenStream(tokens), error_formatter);
        return parse_command_part(ctx);
    }
    private static ParseTree parse_command_part(ParserContext ctx) throws SyntaxError {
        Terminal current = ctx.tokens.current();
        Terminal next;
        ParseTree subtree;
        int rule = (current != null) ? table[57][current.getId()] : -1;
        ParseTree tree = new ParseTree(new NonTerminal(116, "command_part"));
        ctx.nonterminal = "command_part";
        if (current == null) {
            throw new SyntaxError(ctx.error_formatter.unexpectedEof(
                "command_part",
                nonterminal_first.get(116),
                nonterminal_rules.get(116)
            ));
        }
        if (rule == 20) {
            /* $command_part = :cmd_part */
            ctx.rule = rules.get(20);
            tree.setAstTransformation(new AstTransformSubstitution(0));
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_CMD_PART);
            tree.add(next);
            return tree;
        }
        else if (rule == 21) {
            /* $command_part = $cmd_param */
            ctx.rule = rules.get(21);
            tree.setAstTransformation(new AstTransformSubstitution(0));
            subtree = parse_cmd_param(ctx);
            tree.add(subtree);
            return tree;
        }
        throw new SyntaxError(ctx.error_formatter.unexpectedSymbol(
            "command_part",
            current,
            nonterminal_first.get(116),
            rules.get(21)
        ));
    }
    public ParseTree parse_declaration(List<Terminal> tokens, SyntaxErrorFormatter error_formatter) throws SyntaxError {
        ParserContext ctx = new ParserContext(new TokenStream(tokens), error_formatter);
        return parse_declaration(ctx);
    }
    private static ParseTree parse_declaration(ParserContext ctx) throws SyntaxError {
        Terminal current = ctx.tokens.current();
        Terminal next;
        ParseTree subtree;
        int rule = (current != null) ? table[50][current.getId()] : -1;
        ParseTree tree = new ParseTree(new NonTerminal(109, "declaration"));
        ctx.nonterminal = "declaration";
        if (current == null) {
            throw new SyntaxError(ctx.error_formatter.unexpectedEof(
                "declaration",
                nonterminal_first.get(109),
                nonterminal_rules.get(109)
            ));
        }
        if (rule == 50) {
            /* $declaration = $type_e :identifier $_gen12 -> Declaration( type=$0, name=$1, expression=$2 ) */
            ctx.rule = rules.get(50);
            LinkedHashMap<String, Integer> parameters = new LinkedHashMap<String, Integer>();
            parameters.put("type", 0);
            parameters.put("name", 1);
            parameters.put("expression", 2);
            tree.setAstTransformation(new AstTransformNodeCreator("Declaration", parameters));
            subtree = parse_type_e(ctx);
            tree.add(subtree);
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_IDENTIFIER);
            tree.add(next);
            subtree = parse__gen12(ctx);
            tree.add(subtree);
            return tree;
        }
        throw new SyntaxError(ctx.error_formatter.unexpectedSymbol(
            "declaration",
            current,
            nonterminal_first.get(109),
            rules.get(50)
        ));
    }
    public ParseTree parse_document(List<Terminal> tokens, SyntaxErrorFormatter error_formatter) throws SyntaxError {
        ParserContext ctx = new ParserContext(new TokenStream(tokens), error_formatter);
        return parse_document(ctx);
    }
    private static ParseTree parse_document(ParserContext ctx) throws SyntaxError {
        Terminal current = ctx.tokens.current();
        Terminal next;
        ParseTree subtree;
        int rule = (current != null) ? table[13][current.getId()] : -1;
        ParseTree tree = new ParseTree(new NonTerminal(72, "document"));
        ctx.nonterminal = "document";
        if ( current != null &&
             !nonterminal_first.get(72).contains(terminal_map.get(current.getId())) &&
              nonterminal_follow.get(72).contains(terminal_map.get(current.getId())) ) {
            return tree;
        }
        if (current == null) {
            return tree;
        }
        if (rule == 2) {
            /* $document = $_gen0 $_gen1 -> Namespace( imports=$0, body=$1 ) */
            ctx.rule = rules.get(2);
            LinkedHashMap<String, Integer> parameters = new LinkedHashMap<String, Integer>();
            parameters.put("imports", 0);
            parameters.put("body", 1);
            tree.setAstTransformation(new AstTransformNodeCreator("Namespace", parameters));
            subtree = parse__gen0(ctx);
            tree.add(subtree);
            subtree = parse__gen1(ctx);
            tree.add(subtree);
            return tree;
        }
        return tree;
    }
    public ParseTree parse_if_stmt(List<Terminal> tokens, SyntaxErrorFormatter error_formatter) throws SyntaxError {
        ParserContext ctx = new ParserContext(new TokenStream(tokens), error_formatter);
        return parse_if_stmt(ctx);
    }
    private static ParseTree parse_if_stmt(ParserContext ctx) throws SyntaxError {
        Terminal current = ctx.tokens.current();
        Terminal next;
        ParseTree subtree;
        int rule = (current != null) ? table[17][current.getId()] : -1;
        ParseTree tree = new ParseTree(new NonTerminal(76, "if_stmt"));
        ctx.nonterminal = "if_stmt";
        if (current == null) {
            throw new SyntaxError(ctx.error_formatter.unexpectedEof(
                "if_stmt",
                nonterminal_first.get(76),
                nonterminal_rules.get(76)
            ));
        }
        if (rule == 86) {
            /* $if_stmt = :if :lparen $e :rparen :lbrace $_gen13 :rbrace -> If( expression=$2, body=$5 ) */
            ctx.rule = rules.get(86);
            LinkedHashMap<String, Integer> parameters = new LinkedHashMap<String, Integer>();
            parameters.put("expression", 2);
            parameters.put("body", 5);
            tree.setAstTransformation(new AstTransformNodeCreator("If", parameters));
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_IF);
            tree.add(next);
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_LPAREN);
            tree.add(next);
            subtree = parse_e(ctx);
            tree.add(subtree);
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_RPAREN);
            tree.add(next);
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_LBRACE);
            tree.add(next);
            subtree = parse__gen13(ctx);
            tree.add(subtree);
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_RBRACE);
            tree.add(next);
            return tree;
        }
        throw new SyntaxError(ctx.error_formatter.unexpectedSymbol(
            "if_stmt",
            current,
            nonterminal_first.get(76),
            rules.get(86)
        ));
    }
    public ParseTree parse_import(List<Terminal> tokens, SyntaxErrorFormatter error_formatter) throws SyntaxError {
        ParserContext ctx = new ParserContext(new TokenStream(tokens), error_formatter);
        return parse_import(ctx);
    }
    private static ParseTree parse_import(ParserContext ctx) throws SyntaxError {
        Terminal current = ctx.tokens.current();
        Terminal next;
        ParseTree subtree;
        int rule = (current != null) ? table[32][current.getId()] : -1;
        ParseTree tree = new ParseTree(new NonTerminal(91, "import"));
        ctx.nonterminal = "import";
        if (current == null) {
            throw new SyntaxError(ctx.error_formatter.unexpectedEof(
                "import",
                nonterminal_first.get(91),
                nonterminal_rules.get(91)
            ));
        }
        if (rule == 8) {
            /* $import = :import :string $_gen2 -> Import( uri=$1, namespace=$2 ) */
            ctx.rule = rules.get(8);
            LinkedHashMap<String, Integer> parameters = new LinkedHashMap<String, Integer>();
            parameters.put("uri", 1);
            parameters.put("namespace", 2);
            tree.setAstTransformation(new AstTransformNodeCreator("Import", parameters));
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_IMPORT);
            tree.add(next);
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_STRING);
            tree.add(next);
            subtree = parse__gen2(ctx);
            tree.add(subtree);
            return tree;
        }
        throw new SyntaxError(ctx.error_formatter.unexpectedSymbol(
            "import",
            current,
            nonterminal_first.get(91),
            rules.get(8)
        ));
    }
    public ParseTree parse_import_namespace(List<Terminal> tokens, SyntaxErrorFormatter error_formatter) throws SyntaxError {
        ParserContext ctx = new ParserContext(new TokenStream(tokens), error_formatter);
        return parse_import_namespace(ctx);
    }
    private static ParseTree parse_import_namespace(ParserContext ctx) throws SyntaxError {
        Terminal current = ctx.tokens.current();
        Terminal next;
        ParseTree subtree;
        int rule = (current != null) ? table[30][current.getId()] : -1;
        ParseTree tree = new ParseTree(new NonTerminal(89, "import_namespace"));
        ctx.nonterminal = "import_namespace";
        if (current == null) {
            throw new SyntaxError(ctx.error_formatter.unexpectedEof(
                "import_namespace",
                nonterminal_first.get(89),
                nonterminal_rules.get(89)
            ));
        }
        if (rule == 9) {
            /* $import_namespace = :as :identifier -> $1 */
            ctx.rule = rules.get(9);
            tree.setAstTransformation(new AstTransformSubstitution(1));
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_AS);
            tree.add(next);
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_IDENTIFIER);
            tree.add(next);
            return tree;
        }
        throw new SyntaxError(ctx.error_formatter.unexpectedSymbol(
            "import_namespace",
            current,
            nonterminal_first.get(89),
            rules.get(9)
        ));
    }
    public ParseTree parse_kv(List<Terminal> tokens, SyntaxErrorFormatter error_formatter) throws SyntaxError {
        ParserContext ctx = new ParserContext(new TokenStream(tokens), error_formatter);
        return parse_kv(ctx);
    }
    private static ParseTree parse_kv(ParserContext ctx) throws SyntaxError {
        Terminal current = ctx.tokens.current();
        Terminal next;
        ParseTree subtree;
        int rule = (current != null) ? table[55][current.getId()] : -1;
        ParseTree tree = new ParseTree(new NonTerminal(114, "kv"));
        ctx.nonterminal = "kv";
        if (current == null) {
            throw new SyntaxError(ctx.error_formatter.unexpectedEof(
                "kv",
                nonterminal_first.get(114),
                nonterminal_rules.get(114)
            ));
        }
        if (rule == 31) {
            /* $kv = :identifier :colon $e -> RuntimeAttribute( key=$0, value=$2 ) */
            ctx.rule = rules.get(31);
            LinkedHashMap<String, Integer> parameters = new LinkedHashMap<String, Integer>();
            parameters.put("key", 0);
            parameters.put("value", 2);
            tree.setAstTransformation(new AstTransformNodeCreator("RuntimeAttribute", parameters));
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_IDENTIFIER);
            tree.add(next);
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_COLON);
            tree.add(next);
            subtree = parse_e(ctx);
            tree.add(subtree);
            return tree;
        }
        throw new SyntaxError(ctx.error_formatter.unexpectedSymbol(
            "kv",
            current,
            nonterminal_first.get(114),
            rules.get(31)
        ));
    }
    public ParseTree parse_map(List<Terminal> tokens, SyntaxErrorFormatter error_formatter) throws SyntaxError {
        ParserContext ctx = new ParserContext(new TokenStream(tokens), error_formatter);
        return parse_map(ctx);
    }
    private static ParseTree parse_map(ParserContext ctx) throws SyntaxError {
        Terminal current = ctx.tokens.current();
        Terminal next;
        ParseTree subtree;
        int rule = (current != null) ? table[59][current.getId()] : -1;
        ParseTree tree = new ParseTree(new NonTerminal(118, "map"));
        ctx.nonterminal = "map";
        if (current == null) {
            throw new SyntaxError(ctx.error_formatter.unexpectedEof(
                "map",
                nonterminal_first.get(118),
                nonterminal_rules.get(118)
            ));
        }
        if (rule == 30) {
            /* $map = :lbrace $_gen8 :rbrace -> $1 */
            ctx.rule = rules.get(30);
            tree.setAstTransformation(new AstTransformSubstitution(1));
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_LBRACE);
            tree.add(next);
            subtree = parse__gen8(ctx);
            tree.add(subtree);
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_RBRACE);
            tree.add(next);
            return tree;
        }
        throw new SyntaxError(ctx.error_formatter.unexpectedSymbol(
            "map",
            current,
            nonterminal_first.get(118),
            rules.get(30)
        ));
    }
    public ParseTree parse_map_kv(List<Terminal> tokens, SyntaxErrorFormatter error_formatter) throws SyntaxError {
        ParserContext ctx = new ParserContext(new TokenStream(tokens), error_formatter);
        return parse_map_kv(ctx);
    }
    private static ParseTree parse_map_kv(ParserContext ctx) throws SyntaxError {
        Terminal current = ctx.tokens.current();
        Terminal next;
        ParseTree subtree;
        int rule = (current != null) ? table[60][current.getId()] : -1;
        ParseTree tree = new ParseTree(new NonTerminal(119, "map_kv"));
        ctx.nonterminal = "map_kv";
        if (current == null) {
            throw new SyntaxError(ctx.error_formatter.unexpectedEof(
                "map_kv",
                nonterminal_first.get(119),
                nonterminal_rules.get(119)
            ));
        }
        if (rule == 52) {
            /* $map_kv = $e :colon $e -> MapLiteralKv( key=$0, value=$2 ) */
            ctx.rule = rules.get(52);
            LinkedHashMap<String, Integer> parameters = new LinkedHashMap<String, Integer>();
            parameters.put("key", 0);
            parameters.put("value", 2);
            tree.setAstTransformation(new AstTransformNodeCreator("MapLiteralKv", parameters));
            subtree = parse_e(ctx);
            tree.add(subtree);
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_COLON);
            tree.add(next);
            subtree = parse_e(ctx);
            tree.add(subtree);
            return tree;
        }
        throw new SyntaxError(ctx.error_formatter.unexpectedSymbol(
            "map_kv",
            current,
            nonterminal_first.get(119),
            rules.get(52)
        ));
    }
    public ParseTree parse_mapping(List<Terminal> tokens, SyntaxErrorFormatter error_formatter) throws SyntaxError {
        ParserContext ctx = new ParserContext(new TokenStream(tokens), error_formatter);
        return parse_mapping(ctx);
    }
    private static ParseTree parse_mapping(ParserContext ctx) throws SyntaxError {
        Terminal current = ctx.tokens.current();
        Terminal next;
        ParseTree subtree;
        int rule = (current != null) ? table[63][current.getId()] : -1;
        ParseTree tree = new ParseTree(new NonTerminal(122, "mapping"));
        ctx.nonterminal = "mapping";
        if (current == null) {
            throw new SyntaxError(ctx.error_formatter.unexpectedEof(
                "mapping",
                nonterminal_first.get(122),
                nonterminal_rules.get(122)
            ));
        }
        if (rule == 72) {
            /* $mapping = :identifier :equal $e -> IOMapping( key=$0, value=$2 ) */
            ctx.rule = rules.get(72);
            LinkedHashMap<String, Integer> parameters = new LinkedHashMap<String, Integer>();
            parameters.put("key", 0);
            parameters.put("value", 2);
            tree.setAstTransformation(new AstTransformNodeCreator("IOMapping", parameters));
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_IDENTIFIER);
            tree.add(next);
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_EQUAL);
            tree.add(next);
            subtree = parse_e(ctx);
            tree.add(subtree);
            return tree;
        }
        throw new SyntaxError(ctx.error_formatter.unexpectedSymbol(
            "mapping",
            current,
            nonterminal_first.get(122),
            rules.get(72)
        ));
    }
    public ParseTree parse_meta(List<Terminal> tokens, SyntaxErrorFormatter error_formatter) throws SyntaxError {
        ParserContext ctx = new ParserContext(new TokenStream(tokens), error_formatter);
        return parse_meta(ctx);
    }
    private static ParseTree parse_meta(ParserContext ctx) throws SyntaxError {
        Terminal current = ctx.tokens.current();
        Terminal next;
        ParseTree subtree;
        int rule = (current != null) ? table[45][current.getId()] : -1;
        ParseTree tree = new ParseTree(new NonTerminal(104, "meta"));
        ctx.nonterminal = "meta";
        if (current == null) {
            throw new SyntaxError(ctx.error_formatter.unexpectedEof(
                "meta",
                nonterminal_first.get(104),
                nonterminal_rules.get(104)
            ));
        }
        if (rule == 32) {
            /* $meta = :meta $meta_map -> Meta( map=$1 ) */
            ctx.rule = rules.get(32);
            LinkedHashMap<String, Integer> parameters = new LinkedHashMap<String, Integer>();
            parameters.put("map", 1);
            tree.setAstTransformation(new AstTransformNodeCreator("Meta", parameters));
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_META);
            tree.add(next);
            subtree = parse_meta_map(ctx);
            tree.add(subtree);
            return tree;
        }
        throw new SyntaxError(ctx.error_formatter.unexpectedSymbol(
            "meta",
            current,
            nonterminal_first.get(104),
            rules.get(32)
        ));
    }
    public ParseTree parse_meta_array(List<Terminal> tokens, SyntaxErrorFormatter error_formatter) throws SyntaxError {
        ParserContext ctx = new ParserContext(new TokenStream(tokens), error_formatter);
        return parse_meta_array(ctx);
    }
    private static ParseTree parse_meta_array(ParserContext ctx) throws SyntaxError {
        Terminal current = ctx.tokens.current();
        Terminal next;
        ParseTree subtree;
        int rule = (current != null) ? table[15][current.getId()] : -1;
        ParseTree tree = new ParseTree(new NonTerminal(74, "meta_array"));
        ctx.nonterminal = "meta_array";
        if (current == null) {
            throw new SyntaxError(ctx.error_formatter.unexpectedEof(
                "meta_array",
                nonterminal_first.get(74),
                nonterminal_rules.get(74)
            ));
        }
        if (rule == 47) {
            /* $meta_array = :lsquare $_gen11 :rsquare */
            ctx.rule = rules.get(47);
            tree.setAstTransformation(new AstTransformSubstitution(0));
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_LSQUARE);
            tree.add(next);
            subtree = parse__gen11(ctx);
            tree.add(subtree);
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_RSQUARE);
            tree.add(next);
            return tree;
        }
        throw new SyntaxError(ctx.error_formatter.unexpectedSymbol(
            "meta_array",
            current,
            nonterminal_first.get(74),
            rules.get(47)
        ));
    }
    public ParseTree parse_meta_kv(List<Terminal> tokens, SyntaxErrorFormatter error_formatter) throws SyntaxError {
        ParserContext ctx = new ParserContext(new TokenStream(tokens), error_formatter);
        return parse_meta_kv(ctx);
    }
    private static ParseTree parse_meta_kv(ParserContext ctx) throws SyntaxError {
        Terminal current = ctx.tokens.current();
        Terminal next;
        ParseTree subtree;
        int rule = (current != null) ? table[28][current.getId()] : -1;
        ParseTree tree = new ParseTree(new NonTerminal(87, "meta_kv"));
        ctx.nonterminal = "meta_kv";
        if (current == null) {
            throw new SyntaxError(ctx.error_formatter.unexpectedEof(
                "meta_kv",
                nonterminal_first.get(87),
                nonterminal_rules.get(87)
            ));
        }
        if (rule == 36) {
            /* $meta_kv = :identifier :colon $meta_value */
            ctx.rule = rules.get(36);
            tree.setAstTransformation(new AstTransformSubstitution(0));
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_IDENTIFIER);
            tree.add(next);
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_COLON);
            tree.add(next);
            subtree = parse_meta_value(ctx);
            tree.add(subtree);
            return tree;
        }
        throw new SyntaxError(ctx.error_formatter.unexpectedSymbol(
            "meta_kv",
            current,
            nonterminal_first.get(87),
            rules.get(36)
        ));
    }
    public ParseTree parse_meta_map(List<Terminal> tokens, SyntaxErrorFormatter error_formatter) throws SyntaxError {
        ParserContext ctx = new ParserContext(new TokenStream(tokens), error_formatter);
        return parse_meta_map(ctx);
    }
    private static ParseTree parse_meta_map(ParserContext ctx) throws SyntaxError {
        Terminal current = ctx.tokens.current();
        Terminal next;
        ParseTree subtree;
        int rule = (current != null) ? table[33][current.getId()] : -1;
        ParseTree tree = new ParseTree(new NonTerminal(92, "meta_map"));
        ctx.nonterminal = "meta_map";
        if (current == null) {
            throw new SyntaxError(ctx.error_formatter.unexpectedEof(
                "meta_map",
                nonterminal_first.get(92),
                nonterminal_rules.get(92)
            ));
        }
        if (rule == 35) {
            /* $meta_map = :lbrace $_gen9 :rbrace -> $1 */
            ctx.rule = rules.get(35);
            tree.setAstTransformation(new AstTransformSubstitution(1));
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_LBRACE);
            tree.add(next);
            subtree = parse__gen9(ctx);
            tree.add(subtree);
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_RBRACE);
            tree.add(next);
            return tree;
        }
        throw new SyntaxError(ctx.error_formatter.unexpectedSymbol(
            "meta_map",
            current,
            nonterminal_first.get(92),
            rules.get(35)
        ));
    }
    public ParseTree parse_meta_object(List<Terminal> tokens, SyntaxErrorFormatter error_formatter) throws SyntaxError {
        ParserContext ctx = new ParserContext(new TokenStream(tokens), error_formatter);
        return parse_meta_object(ctx);
    }
    private static ParseTree parse_meta_object(ParserContext ctx) throws SyntaxError {
        Terminal current = ctx.tokens.current();
        Terminal next;
        ParseTree subtree;
        int rule = (current != null) ? table[35][current.getId()] : -1;
        ParseTree tree = new ParseTree(new NonTerminal(94, "meta_object"));
        ctx.nonterminal = "meta_object";
        if (current == null) {
            throw new SyntaxError(ctx.error_formatter.unexpectedEof(
                "meta_object",
                nonterminal_first.get(94),
                nonterminal_rules.get(94)
            ));
        }
        if (rule == 45) {
            /* $meta_object = :lbrace $_gen10 :rbrace */
            ctx.rule = rules.get(45);
            tree.setAstTransformation(new AstTransformSubstitution(0));
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_LBRACE);
            tree.add(next);
            subtree = parse__gen10(ctx);
            tree.add(subtree);
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_RBRACE);
            tree.add(next);
            return tree;
        }
        throw new SyntaxError(ctx.error_formatter.unexpectedSymbol(
            "meta_object",
            current,
            nonterminal_first.get(94),
            rules.get(45)
        ));
    }
    public ParseTree parse_meta_value(List<Terminal> tokens, SyntaxErrorFormatter error_formatter) throws SyntaxError {
        ParserContext ctx = new ParserContext(new TokenStream(tokens), error_formatter);
        return parse_meta_value(ctx);
    }
    private static ParseTree parse_meta_value(ParserContext ctx) throws SyntaxError {
        Terminal current = ctx.tokens.current();
        Terminal next;
        ParseTree subtree;
        int rule = (current != null) ? table[37][current.getId()] : -1;
        ParseTree tree = new ParseTree(new NonTerminal(96, "meta_value"));
        ctx.nonterminal = "meta_value";
        if (current == null) {
            throw new SyntaxError(ctx.error_formatter.unexpectedEof(
                "meta_value",
                nonterminal_first.get(96),
                nonterminal_rules.get(96)
            ));
        }
        if (rule == 37) {
            /* $meta_value = :string */
            ctx.rule = rules.get(37);
            tree.setAstTransformation(new AstTransformSubstitution(0));
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_STRING);
            tree.add(next);
            return tree;
        }
        else if (rule == 38) {
            /* $meta_value = :integer */
            ctx.rule = rules.get(38);
            tree.setAstTransformation(new AstTransformSubstitution(0));
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_INTEGER);
            tree.add(next);
            return tree;
        }
        else if (rule == 39) {
            /* $meta_value = :float */
            ctx.rule = rules.get(39);
            tree.setAstTransformation(new AstTransformSubstitution(0));
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_FLOAT);
            tree.add(next);
            return tree;
        }
        else if (rule == 40) {
            /* $meta_value = :boolean */
            ctx.rule = rules.get(40);
            tree.setAstTransformation(new AstTransformSubstitution(0));
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_BOOLEAN);
            tree.add(next);
            return tree;
        }
        else if (rule == 41) {
            /* $meta_value = :null */
            ctx.rule = rules.get(41);
            tree.setAstTransformation(new AstTransformSubstitution(0));
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_NULL);
            tree.add(next);
            return tree;
        }
        else if (rule == 42) {
            /* $meta_value = $meta_object */
            ctx.rule = rules.get(42);
            tree.setAstTransformation(new AstTransformSubstitution(0));
            subtree = parse_meta_object(ctx);
            tree.add(subtree);
            return tree;
        }
        else if (rule == 43) {
            /* $meta_value = $meta_array */
            ctx.rule = rules.get(43);
            tree.setAstTransformation(new AstTransformSubstitution(0));
            subtree = parse_meta_array(ctx);
            tree.add(subtree);
            return tree;
        }
        throw new SyntaxError(ctx.error_formatter.unexpectedSymbol(
            "meta_value",
            current,
            nonterminal_first.get(96),
            rules.get(43)
        ));
    }
    public ParseTree parse_object_kv(List<Terminal> tokens, SyntaxErrorFormatter error_formatter) throws SyntaxError {
        ParserContext ctx = new ParserContext(new TokenStream(tokens), error_formatter);
        return parse_object_kv(ctx);
    }
    private static ParseTree parse_object_kv(ParserContext ctx) throws SyntaxError {
        Terminal current = ctx.tokens.current();
        Terminal next;
        ParseTree subtree;
        int rule = (current != null) ? table[51][current.getId()] : -1;
        ParseTree tree = new ParseTree(new NonTerminal(110, "object_kv"));
        ctx.nonterminal = "object_kv";
        if (current == null) {
            throw new SyntaxError(ctx.error_formatter.unexpectedEof(
                "object_kv",
                nonterminal_first.get(110),
                nonterminal_rules.get(110)
            ));
        }
        if (rule == 88) {
            /* $object_kv = :identifier :colon $e -> ObjectKV( key=$0, value=$2 ) */
            ctx.rule = rules.get(88);
            LinkedHashMap<String, Integer> parameters = new LinkedHashMap<String, Integer>();
            parameters.put("key", 0);
            parameters.put("value", 2);
            tree.setAstTransformation(new AstTransformNodeCreator("ObjectKV", parameters));
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_IDENTIFIER);
            tree.add(next);
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_COLON);
            tree.add(next);
            subtree = parse_e(ctx);
            tree.add(subtree);
            return tree;
        }
        throw new SyntaxError(ctx.error_formatter.unexpectedSymbol(
            "object_kv",
            current,
            nonterminal_first.get(110),
            rules.get(88)
        ));
    }
    public ParseTree parse_output_kv(List<Terminal> tokens, SyntaxErrorFormatter error_formatter) throws SyntaxError {
        ParserContext ctx = new ParserContext(new TokenStream(tokens), error_formatter);
        return parse_output_kv(ctx);
    }
    private static ParseTree parse_output_kv(ParserContext ctx) throws SyntaxError {
        Terminal current = ctx.tokens.current();
        Terminal next;
        ParseTree subtree;
        int rule = (current != null) ? table[64][current.getId()] : -1;
        ParseTree tree = new ParseTree(new NonTerminal(123, "output_kv"));
        ctx.nonterminal = "output_kv";
        if (current == null) {
            throw new SyntaxError(ctx.error_formatter.unexpectedEof(
                "output_kv",
                nonterminal_first.get(123),
                nonterminal_rules.get(123)
            ));
        }
        if (rule == 27) {
            /* $output_kv = $type_e :identifier :equal $e -> Output( type=$0, name=$1, expression=$3 ) */
            ctx.rule = rules.get(27);
            LinkedHashMap<String, Integer> parameters = new LinkedHashMap<String, Integer>();
            parameters.put("type", 0);
            parameters.put("name", 1);
            parameters.put("expression", 3);
            tree.setAstTransformation(new AstTransformNodeCreator("Output", parameters));
            subtree = parse_type_e(ctx);
            tree.add(subtree);
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_IDENTIFIER);
            tree.add(next);
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_EQUAL);
            tree.add(next);
            subtree = parse_e(ctx);
            tree.add(subtree);
            return tree;
        }
        throw new SyntaxError(ctx.error_formatter.unexpectedSymbol(
            "output_kv",
            current,
            nonterminal_first.get(123),
            rules.get(27)
        ));
    }
    public ParseTree parse_outputs(List<Terminal> tokens, SyntaxErrorFormatter error_formatter) throws SyntaxError {
        ParserContext ctx = new ParserContext(new TokenStream(tokens), error_formatter);
        return parse_outputs(ctx);
    }
    private static ParseTree parse_outputs(ParserContext ctx) throws SyntaxError {
        Terminal current = ctx.tokens.current();
        Terminal next;
        ParseTree subtree;
        int rule = (current != null) ? table[25][current.getId()] : -1;
        ParseTree tree = new ParseTree(new NonTerminal(84, "outputs"));
        ctx.nonterminal = "outputs";
        if (current == null) {
            throw new SyntaxError(ctx.error_formatter.unexpectedEof(
                "outputs",
                nonterminal_first.get(84),
                nonterminal_rules.get(84)
            ));
        }
        if (rule == 26) {
            /* $outputs = :output :lbrace $_gen7 :rbrace -> Outputs( attributes=$2 ) */
            ctx.rule = rules.get(26);
            LinkedHashMap<String, Integer> parameters = new LinkedHashMap<String, Integer>();
            parameters.put("attributes", 2);
            tree.setAstTransformation(new AstTransformNodeCreator("Outputs", parameters));
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_OUTPUT);
            tree.add(next);
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_LBRACE);
            tree.add(next);
            subtree = parse__gen7(ctx);
            tree.add(subtree);
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_RBRACE);
            tree.add(next);
            return tree;
        }
        throw new SyntaxError(ctx.error_formatter.unexpectedSymbol(
            "outputs",
            current,
            nonterminal_first.get(84),
            rules.get(26)
        ));
    }
    public ParseTree parse_parameter_meta(List<Terminal> tokens, SyntaxErrorFormatter error_formatter) throws SyntaxError {
        ParserContext ctx = new ParserContext(new TokenStream(tokens), error_formatter);
        return parse_parameter_meta(ctx);
    }
    private static ParseTree parse_parameter_meta(ParserContext ctx) throws SyntaxError {
        Terminal current = ctx.tokens.current();
        Terminal next;
        ParseTree subtree;
        int rule = (current != null) ? table[61][current.getId()] : -1;
        ParseTree tree = new ParseTree(new NonTerminal(120, "parameter_meta"));
        ctx.nonterminal = "parameter_meta";
        if (current == null) {
            throw new SyntaxError(ctx.error_formatter.unexpectedEof(
                "parameter_meta",
                nonterminal_first.get(120),
                nonterminal_rules.get(120)
            ));
        }
        if (rule == 33) {
            /* $parameter_meta = :parameter_meta $meta_map -> ParameterMeta( map=$1 ) */
            ctx.rule = rules.get(33);
            LinkedHashMap<String, Integer> parameters = new LinkedHashMap<String, Integer>();
            parameters.put("map", 1);
            tree.setAstTransformation(new AstTransformNodeCreator("ParameterMeta", parameters));
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_PARAMETER_META);
            tree.add(next);
            subtree = parse_meta_map(ctx);
            tree.add(subtree);
            return tree;
        }
        throw new SyntaxError(ctx.error_formatter.unexpectedSymbol(
            "parameter_meta",
            current,
            nonterminal_first.get(120),
            rules.get(33)
        ));
    }
    public ParseTree parse_runtime(List<Terminal> tokens, SyntaxErrorFormatter error_formatter) throws SyntaxError {
        ParserContext ctx = new ParserContext(new TokenStream(tokens), error_formatter);
        return parse_runtime(ctx);
    }
    private static ParseTree parse_runtime(ParserContext ctx) throws SyntaxError {
        Terminal current = ctx.tokens.current();
        Terminal next;
        ParseTree subtree;
        int rule = (current != null) ? table[21][current.getId()] : -1;
        ParseTree tree = new ParseTree(new NonTerminal(80, "runtime"));
        ctx.nonterminal = "runtime";
        if (current == null) {
            throw new SyntaxError(ctx.error_formatter.unexpectedEof(
                "runtime",
                nonterminal_first.get(80),
                nonterminal_rules.get(80)
            ));
        }
        if (rule == 28) {
            /* $runtime = :runtime $map -> Runtime( map=$1 ) */
            ctx.rule = rules.get(28);
            LinkedHashMap<String, Integer> parameters = new LinkedHashMap<String, Integer>();
            parameters.put("map", 1);
            tree.setAstTransformation(new AstTransformNodeCreator("Runtime", parameters));
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_RUNTIME);
            tree.add(next);
            subtree = parse_map(ctx);
            tree.add(subtree);
            return tree;
        }
        throw new SyntaxError(ctx.error_formatter.unexpectedSymbol(
            "runtime",
            current,
            nonterminal_first.get(80),
            rules.get(28)
        ));
    }
    public ParseTree parse_scatter(List<Terminal> tokens, SyntaxErrorFormatter error_formatter) throws SyntaxError {
        ParserContext ctx = new ParserContext(new TokenStream(tokens), error_formatter);
        return parse_scatter(ctx);
    }
    private static ParseTree parse_scatter(ParserContext ctx) throws SyntaxError {
        Terminal current = ctx.tokens.current();
        Terminal next;
        ParseTree subtree;
        int rule = (current != null) ? table[52][current.getId()] : -1;
        ParseTree tree = new ParseTree(new NonTerminal(111, "scatter"));
        ctx.nonterminal = "scatter";
        if (current == null) {
            throw new SyntaxError(ctx.error_formatter.unexpectedEof(
                "scatter",
                nonterminal_first.get(111),
                nonterminal_rules.get(111)
            ));
        }
        if (rule == 87) {
            /* $scatter = :scatter :lparen :identifier :in $e :rparen :lbrace $_gen13 :rbrace -> Scatter( item=$2, collection=$4, body=$7 ) */
            ctx.rule = rules.get(87);
            LinkedHashMap<String, Integer> parameters = new LinkedHashMap<String, Integer>();
            parameters.put("item", 2);
            parameters.put("collection", 4);
            parameters.put("body", 7);
            tree.setAstTransformation(new AstTransformNodeCreator("Scatter", parameters));
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_SCATTER);
            tree.add(next);
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_LPAREN);
            tree.add(next);
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_IDENTIFIER);
            tree.add(next);
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_IN);
            tree.add(next);
            subtree = parse_e(ctx);
            tree.add(subtree);
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_RPAREN);
            tree.add(next);
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_LBRACE);
            tree.add(next);
            subtree = parse__gen13(ctx);
            tree.add(subtree);
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_RBRACE);
            tree.add(next);
            return tree;
        }
        throw new SyntaxError(ctx.error_formatter.unexpectedSymbol(
            "scatter",
            current,
            nonterminal_first.get(111),
            rules.get(87)
        ));
    }
    public ParseTree parse_sections(List<Terminal> tokens, SyntaxErrorFormatter error_formatter) throws SyntaxError {
        ParserContext ctx = new ParserContext(new TokenStream(tokens), error_formatter);
        return parse_sections(ctx);
    }
    private static ParseTree parse_sections(ParserContext ctx) throws SyntaxError {
        Terminal current = ctx.tokens.current();
        Terminal next;
        ParseTree subtree;
        int rule = (current != null) ? table[7][current.getId()] : -1;
        ParseTree tree = new ParseTree(new NonTerminal(66, "sections"));
        ctx.nonterminal = "sections";
        if (current == null) {
            throw new SyntaxError(ctx.error_formatter.unexpectedEof(
                "sections",
                nonterminal_first.get(66),
                nonterminal_rules.get(66)
            ));
        }
        if (rule == 13) {
            /* $sections = $command */
            ctx.rule = rules.get(13);
            tree.setAstTransformation(new AstTransformSubstitution(0));
            subtree = parse_command(ctx);
            tree.add(subtree);
            return tree;
        }
        else if (rule == 14) {
            /* $sections = $outputs */
            ctx.rule = rules.get(14);
            tree.setAstTransformation(new AstTransformSubstitution(0));
            subtree = parse_outputs(ctx);
            tree.add(subtree);
            return tree;
        }
        else if (rule == 15) {
            /* $sections = $runtime */
            ctx.rule = rules.get(15);
            tree.setAstTransformation(new AstTransformSubstitution(0));
            subtree = parse_runtime(ctx);
            tree.add(subtree);
            return tree;
        }
        else if (rule == 16) {
            /* $sections = $parameter_meta */
            ctx.rule = rules.get(16);
            tree.setAstTransformation(new AstTransformSubstitution(0));
            subtree = parse_parameter_meta(ctx);
            tree.add(subtree);
            return tree;
        }
        else if (rule == 17) {
            /* $sections = $meta */
            ctx.rule = rules.get(17);
            tree.setAstTransformation(new AstTransformSubstitution(0));
            subtree = parse_meta(ctx);
            tree.add(subtree);
            return tree;
        }
        throw new SyntaxError(ctx.error_formatter.unexpectedSymbol(
            "sections",
            current,
            nonterminal_first.get(66),
            rules.get(17)
        ));
    }
    public ParseTree parse_setter(List<Terminal> tokens, SyntaxErrorFormatter error_formatter) throws SyntaxError {
        ParserContext ctx = new ParserContext(new TokenStream(tokens), error_formatter);
        return parse_setter(ctx);
    }
    private static ParseTree parse_setter(ParserContext ctx) throws SyntaxError {
        Terminal current = ctx.tokens.current();
        Terminal next;
        ParseTree subtree;
        int rule = (current != null) ? table[1][current.getId()] : -1;
        ParseTree tree = new ParseTree(new NonTerminal(60, "setter"));
        ctx.nonterminal = "setter";
        if (current == null) {
            throw new SyntaxError(ctx.error_formatter.unexpectedEof(
                "setter",
                nonterminal_first.get(60),
                nonterminal_rules.get(60)
            ));
        }
        if (rule == 51) {
            /* $setter = :equal $e -> $1 */
            ctx.rule = rules.get(51);
            tree.setAstTransformation(new AstTransformSubstitution(1));
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_EQUAL);
            tree.add(next);
            subtree = parse_e(ctx);
            tree.add(subtree);
            return tree;
        }
        throw new SyntaxError(ctx.error_formatter.unexpectedSymbol(
            "setter",
            current,
            nonterminal_first.get(60),
            rules.get(51)
        ));
    }
    public ParseTree parse_task(List<Terminal> tokens, SyntaxErrorFormatter error_formatter) throws SyntaxError {
        ParserContext ctx = new ParserContext(new TokenStream(tokens), error_formatter);
        return parse_task(ctx);
    }
    private static ParseTree parse_task(ParserContext ctx) throws SyntaxError {
        Terminal current = ctx.tokens.current();
        Terminal next;
        ParseTree subtree;
        int rule = (current != null) ? table[22][current.getId()] : -1;
        ParseTree tree = new ParseTree(new NonTerminal(81, "task"));
        ctx.nonterminal = "task";
        if (current == null) {
            throw new SyntaxError(ctx.error_formatter.unexpectedEof(
                "task",
                nonterminal_first.get(81),
                nonterminal_rules.get(81)
            ));
        }
        if (rule == 12) {
            /* $task = :task :identifier :lbrace $_gen3 $_gen4 :rbrace -> Task( name=$1, declarations=$3, sections=$4 ) */
            ctx.rule = rules.get(12);
            LinkedHashMap<String, Integer> parameters = new LinkedHashMap<String, Integer>();
            parameters.put("name", 1);
            parameters.put("declarations", 3);
            parameters.put("sections", 4);
            tree.setAstTransformation(new AstTransformNodeCreator("Task", parameters));
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_TASK);
            tree.add(next);
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_IDENTIFIER);
            tree.add(next);
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_LBRACE);
            tree.add(next);
            subtree = parse__gen3(ctx);
            tree.add(subtree);
            subtree = parse__gen4(ctx);
            tree.add(subtree);
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_RBRACE);
            tree.add(next);
            return tree;
        }
        throw new SyntaxError(ctx.error_formatter.unexpectedSymbol(
            "task",
            current,
            nonterminal_first.get(81),
            rules.get(12)
        ));
    }
    public ParseTree parse_wf_body_element(List<Terminal> tokens, SyntaxErrorFormatter error_formatter) throws SyntaxError {
        ParserContext ctx = new ParserContext(new TokenStream(tokens), error_formatter);
        return parse_wf_body_element(ctx);
    }
    private static ParseTree parse_wf_body_element(ParserContext ctx) throws SyntaxError {
        Terminal current = ctx.tokens.current();
        Terminal next;
        ParseTree subtree;
        int rule = (current != null) ? table[27][current.getId()] : -1;
        ParseTree tree = new ParseTree(new NonTerminal(86, "wf_body_element"));
        ctx.nonterminal = "wf_body_element";
        if (current == null) {
            throw new SyntaxError(ctx.error_formatter.unexpectedEof(
                "wf_body_element",
                nonterminal_first.get(86),
                nonterminal_rules.get(86)
            ));
        }
        if (rule == 55) {
            /* $wf_body_element = $call */
            ctx.rule = rules.get(55);
            tree.setAstTransformation(new AstTransformSubstitution(0));
            subtree = parse_call(ctx);
            tree.add(subtree);
            return tree;
        }
        else if (rule == 56) {
            /* $wf_body_element = $declaration */
            ctx.rule = rules.get(56);
            tree.setAstTransformation(new AstTransformSubstitution(0));
            subtree = parse_declaration(ctx);
            tree.add(subtree);
            return tree;
        }
        else if (rule == 57) {
            /* $wf_body_element = $while_loop */
            ctx.rule = rules.get(57);
            tree.setAstTransformation(new AstTransformSubstitution(0));
            subtree = parse_while_loop(ctx);
            tree.add(subtree);
            return tree;
        }
        else if (rule == 58) {
            /* $wf_body_element = $if_stmt */
            ctx.rule = rules.get(58);
            tree.setAstTransformation(new AstTransformSubstitution(0));
            subtree = parse_if_stmt(ctx);
            tree.add(subtree);
            return tree;
        }
        else if (rule == 59) {
            /* $wf_body_element = $scatter */
            ctx.rule = rules.get(59);
            tree.setAstTransformation(new AstTransformSubstitution(0));
            subtree = parse_scatter(ctx);
            tree.add(subtree);
            return tree;
        }
        else if (rule == 60) {
            /* $wf_body_element = $wf_outputs */
            ctx.rule = rules.get(60);
            tree.setAstTransformation(new AstTransformSubstitution(0));
            subtree = parse_wf_outputs(ctx);
            tree.add(subtree);
            return tree;
        }
        else if (rule == 61) {
            /* $wf_body_element = $wf_parameter_meta */
            ctx.rule = rules.get(61);
            tree.setAstTransformation(new AstTransformSubstitution(0));
            subtree = parse_wf_parameter_meta(ctx);
            tree.add(subtree);
            return tree;
        }
        else if (rule == 62) {
            /* $wf_body_element = $wf_meta */
            ctx.rule = rules.get(62);
            tree.setAstTransformation(new AstTransformSubstitution(0));
            subtree = parse_wf_meta(ctx);
            tree.add(subtree);
            return tree;
        }
        throw new SyntaxError(ctx.error_formatter.unexpectedSymbol(
            "wf_body_element",
            current,
            nonterminal_first.get(86),
            rules.get(62)
        ));
    }
    public ParseTree parse_wf_meta(List<Terminal> tokens, SyntaxErrorFormatter error_formatter) throws SyntaxError {
        ParserContext ctx = new ParserContext(new TokenStream(tokens), error_formatter);
        return parse_wf_meta(ctx);
    }
    private static ParseTree parse_wf_meta(ParserContext ctx) throws SyntaxError {
        Terminal current = ctx.tokens.current();
        Terminal next;
        ParseTree subtree;
        int rule = (current != null) ? table[3][current.getId()] : -1;
        ParseTree tree = new ParseTree(new NonTerminal(62, "wf_meta"));
        ctx.nonterminal = "wf_meta";
        if (current == null) {
            throw new SyntaxError(ctx.error_formatter.unexpectedEof(
                "wf_meta",
                nonterminal_first.get(62),
                nonterminal_rules.get(62)
            ));
        }
        if (rule == 84) {
            /* $wf_meta = :meta $map -> Meta( map=$1 ) */
            ctx.rule = rules.get(84);
            LinkedHashMap<String, Integer> parameters = new LinkedHashMap<String, Integer>();
            parameters.put("map", 1);
            tree.setAstTransformation(new AstTransformNodeCreator("Meta", parameters));
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_META);
            tree.add(next);
            subtree = parse_map(ctx);
            tree.add(subtree);
            return tree;
        }
        throw new SyntaxError(ctx.error_formatter.unexpectedSymbol(
            "wf_meta",
            current,
            nonterminal_first.get(62),
            rules.get(84)
        ));
    }
    public ParseTree parse_wf_output(List<Terminal> tokens, SyntaxErrorFormatter error_formatter) throws SyntaxError {
        ParserContext ctx = new ParserContext(new TokenStream(tokens), error_formatter);
        return parse_wf_output(ctx);
    }
    private static ParseTree parse_wf_output(ParserContext ctx) throws SyntaxError {
        Terminal current = ctx.tokens.current();
        Terminal next;
        ParseTree subtree;
        int rule = (current != null) ? table[20][current.getId()] : -1;
        ParseTree tree = new ParseTree(new NonTerminal(79, "wf_output"));
        ctx.nonterminal = "wf_output";
        if (current == null) {
            throw new SyntaxError(ctx.error_formatter.unexpectedEof(
                "wf_output",
                nonterminal_first.get(79),
                nonterminal_rules.get(79)
            ));
        }
        if (rule == 76) {
            /* $wf_output = $wf_output_declaration_syntax */
            ctx.rule = rules.get(76);
            tree.setAstTransformation(new AstTransformSubstitution(0));
            subtree = parse_wf_output_declaration_syntax(ctx);
            tree.add(subtree);
            return tree;
        }
        else if (rule == 77) {
            /* $wf_output = $wf_output_wildcard_syntax */
            ctx.rule = rules.get(77);
            tree.setAstTransformation(new AstTransformSubstitution(0));
            subtree = parse_wf_output_wildcard_syntax(ctx);
            tree.add(subtree);
            return tree;
        }
        throw new SyntaxError(ctx.error_formatter.unexpectedSymbol(
            "wf_output",
            current,
            nonterminal_first.get(79),
            rules.get(77)
        ));
    }
    public ParseTree parse_wf_output_declaration_syntax(List<Terminal> tokens, SyntaxErrorFormatter error_formatter) throws SyntaxError {
        ParserContext ctx = new ParserContext(new TokenStream(tokens), error_formatter);
        return parse_wf_output_declaration_syntax(ctx);
    }
    private static ParseTree parse_wf_output_declaration_syntax(ParserContext ctx) throws SyntaxError {
        Terminal current = ctx.tokens.current();
        Terminal next;
        ParseTree subtree;
        int rule = (current != null) ? table[18][current.getId()] : -1;
        ParseTree tree = new ParseTree(new NonTerminal(77, "wf_output_declaration_syntax"));
        ctx.nonterminal = "wf_output_declaration_syntax";
        if (current == null) {
            throw new SyntaxError(ctx.error_formatter.unexpectedEof(
                "wf_output_declaration_syntax",
                nonterminal_first.get(77),
                nonterminal_rules.get(77)
            ));
        }
        if (rule == 78) {
            /* $wf_output_declaration_syntax = $type_e :identifier :equal $e -> WorkflowOutputDeclaration( type=$0, name=$1, expression=$3 ) */
            ctx.rule = rules.get(78);
            LinkedHashMap<String, Integer> parameters = new LinkedHashMap<String, Integer>();
            parameters.put("type", 0);
            parameters.put("name", 1);
            parameters.put("expression", 3);
            tree.setAstTransformation(new AstTransformNodeCreator("WorkflowOutputDeclaration", parameters));
            subtree = parse_type_e(ctx);
            tree.add(subtree);
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_IDENTIFIER);
            tree.add(next);
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_EQUAL);
            tree.add(next);
            subtree = parse_e(ctx);
            tree.add(subtree);
            return tree;
        }
        throw new SyntaxError(ctx.error_formatter.unexpectedSymbol(
            "wf_output_declaration_syntax",
            current,
            nonterminal_first.get(77),
            rules.get(78)
        ));
    }
    public ParseTree parse_wf_output_wildcard(List<Terminal> tokens, SyntaxErrorFormatter error_formatter) throws SyntaxError {
        ParserContext ctx = new ParserContext(new TokenStream(tokens), error_formatter);
        return parse_wf_output_wildcard(ctx);
    }
    private static ParseTree parse_wf_output_wildcard(ParserContext ctx) throws SyntaxError {
        Terminal current = ctx.tokens.current();
        Terminal next;
        ParseTree subtree;
        int rule = (current != null) ? table[23][current.getId()] : -1;
        ParseTree tree = new ParseTree(new NonTerminal(82, "wf_output_wildcard"));
        ctx.nonterminal = "wf_output_wildcard";
        if (current == null) {
            throw new SyntaxError(ctx.error_formatter.unexpectedEof(
                "wf_output_wildcard",
                nonterminal_first.get(82),
                nonterminal_rules.get(82)
            ));
        }
        if (rule == 82) {
            /* $wf_output_wildcard = :dot :asterisk -> $1 */
            ctx.rule = rules.get(82);
            tree.setAstTransformation(new AstTransformSubstitution(1));
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_DOT);
            tree.add(next);
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_ASTERISK);
            tree.add(next);
            return tree;
        }
        throw new SyntaxError(ctx.error_formatter.unexpectedSymbol(
            "wf_output_wildcard",
            current,
            nonterminal_first.get(82),
            rules.get(82)
        ));
    }
    public ParseTree parse_wf_output_wildcard_syntax(List<Terminal> tokens, SyntaxErrorFormatter error_formatter) throws SyntaxError {
        ParserContext ctx = new ParserContext(new TokenStream(tokens), error_formatter);
        return parse_wf_output_wildcard_syntax(ctx);
    }
    private static ParseTree parse_wf_output_wildcard_syntax(ParserContext ctx) throws SyntaxError {
        Terminal current = ctx.tokens.current();
        Terminal next;
        ParseTree subtree;
        int rule = (current != null) ? table[56][current.getId()] : -1;
        ParseTree tree = new ParseTree(new NonTerminal(115, "wf_output_wildcard_syntax"));
        ctx.nonterminal = "wf_output_wildcard_syntax";
        if (current == null) {
            throw new SyntaxError(ctx.error_formatter.unexpectedEof(
                "wf_output_wildcard_syntax",
                nonterminal_first.get(115),
                nonterminal_rules.get(115)
            ));
        }
        if (rule == 81) {
            /* $wf_output_wildcard_syntax = :fqn $_gen19 -> WorkflowOutputWildcard( fqn=$0, wildcard=$1 ) */
            ctx.rule = rules.get(81);
            LinkedHashMap<String, Integer> parameters = new LinkedHashMap<String, Integer>();
            parameters.put("fqn", 0);
            parameters.put("wildcard", 1);
            tree.setAstTransformation(new AstTransformNodeCreator("WorkflowOutputWildcard", parameters));
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_FQN);
            tree.add(next);
            subtree = parse__gen19(ctx);
            tree.add(subtree);
            return tree;
        }
        throw new SyntaxError(ctx.error_formatter.unexpectedSymbol(
            "wf_output_wildcard_syntax",
            current,
            nonterminal_first.get(115),
            rules.get(81)
        ));
    }
    public ParseTree parse_wf_outputs(List<Terminal> tokens, SyntaxErrorFormatter error_formatter) throws SyntaxError {
        ParserContext ctx = new ParserContext(new TokenStream(tokens), error_formatter);
        return parse_wf_outputs(ctx);
    }
    private static ParseTree parse_wf_outputs(ParserContext ctx) throws SyntaxError {
        Terminal current = ctx.tokens.current();
        Terminal next;
        ParseTree subtree;
        int rule = (current != null) ? table[36][current.getId()] : -1;
        ParseTree tree = new ParseTree(new NonTerminal(95, "wf_outputs"));
        ctx.nonterminal = "wf_outputs";
        if (current == null) {
            throw new SyntaxError(ctx.error_formatter.unexpectedEof(
                "wf_outputs",
                nonterminal_first.get(95),
                nonterminal_rules.get(95)
            ));
        }
        if (rule == 75) {
            /* $wf_outputs = :output :lbrace $_gen18 :rbrace -> WorkflowOutputs( outputs=$2 ) */
            ctx.rule = rules.get(75);
            LinkedHashMap<String, Integer> parameters = new LinkedHashMap<String, Integer>();
            parameters.put("outputs", 2);
            tree.setAstTransformation(new AstTransformNodeCreator("WorkflowOutputs", parameters));
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_OUTPUT);
            tree.add(next);
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_LBRACE);
            tree.add(next);
            subtree = parse__gen18(ctx);
            tree.add(subtree);
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_RBRACE);
            tree.add(next);
            return tree;
        }
        throw new SyntaxError(ctx.error_formatter.unexpectedSymbol(
            "wf_outputs",
            current,
            nonterminal_first.get(95),
            rules.get(75)
        ));
    }
    public ParseTree parse_wf_parameter_meta(List<Terminal> tokens, SyntaxErrorFormatter error_formatter) throws SyntaxError {
        ParserContext ctx = new ParserContext(new TokenStream(tokens), error_formatter);
        return parse_wf_parameter_meta(ctx);
    }
    private static ParseTree parse_wf_parameter_meta(ParserContext ctx) throws SyntaxError {
        Terminal current = ctx.tokens.current();
        Terminal next;
        ParseTree subtree;
        int rule = (current != null) ? table[39][current.getId()] : -1;
        ParseTree tree = new ParseTree(new NonTerminal(98, "wf_parameter_meta"));
        ctx.nonterminal = "wf_parameter_meta";
        if (current == null) {
            throw new SyntaxError(ctx.error_formatter.unexpectedEof(
                "wf_parameter_meta",
                nonterminal_first.get(98),
                nonterminal_rules.get(98)
            ));
        }
        if (rule == 83) {
            /* $wf_parameter_meta = :parameter_meta $map -> ParameterMeta( map=$1 ) */
            ctx.rule = rules.get(83);
            LinkedHashMap<String, Integer> parameters = new LinkedHashMap<String, Integer>();
            parameters.put("map", 1);
            tree.setAstTransformation(new AstTransformNodeCreator("ParameterMeta", parameters));
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_PARAMETER_META);
            tree.add(next);
            subtree = parse_map(ctx);
            tree.add(subtree);
            return tree;
        }
        throw new SyntaxError(ctx.error_formatter.unexpectedSymbol(
            "wf_parameter_meta",
            current,
            nonterminal_first.get(98),
            rules.get(83)
        ));
    }
    public ParseTree parse_while_loop(List<Terminal> tokens, SyntaxErrorFormatter error_formatter) throws SyntaxError {
        ParserContext ctx = new ParserContext(new TokenStream(tokens), error_formatter);
        return parse_while_loop(ctx);
    }
    private static ParseTree parse_while_loop(ParserContext ctx) throws SyntaxError {
        Terminal current = ctx.tokens.current();
        Terminal next;
        ParseTree subtree;
        int rule = (current != null) ? table[6][current.getId()] : -1;
        ParseTree tree = new ParseTree(new NonTerminal(65, "while_loop"));
        ctx.nonterminal = "while_loop";
        if (current == null) {
            throw new SyntaxError(ctx.error_formatter.unexpectedEof(
                "while_loop",
                nonterminal_first.get(65),
                nonterminal_rules.get(65)
            ));
        }
        if (rule == 85) {
            /* $while_loop = :while :lparen $e :rparen :lbrace $_gen13 :rbrace -> WhileLoop( expression=$2, body=$5 ) */
            ctx.rule = rules.get(85);
            LinkedHashMap<String, Integer> parameters = new LinkedHashMap<String, Integer>();
            parameters.put("expression", 2);
            parameters.put("body", 5);
            tree.setAstTransformation(new AstTransformNodeCreator("WhileLoop", parameters));
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_WHILE);
            tree.add(next);
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_LPAREN);
            tree.add(next);
            subtree = parse_e(ctx);
            tree.add(subtree);
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_RPAREN);
            tree.add(next);
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_LBRACE);
            tree.add(next);
            subtree = parse__gen13(ctx);
            tree.add(subtree);
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_RBRACE);
            tree.add(next);
            return tree;
        }
        throw new SyntaxError(ctx.error_formatter.unexpectedSymbol(
            "while_loop",
            current,
            nonterminal_first.get(65),
            rules.get(85)
        ));
    }
    public ParseTree parse_workflow(List<Terminal> tokens, SyntaxErrorFormatter error_formatter) throws SyntaxError {
        ParserContext ctx = new ParserContext(new TokenStream(tokens), error_formatter);
        return parse_workflow(ctx);
    }
    private static ParseTree parse_workflow(ParserContext ctx) throws SyntaxError {
        Terminal current = ctx.tokens.current();
        Terminal next;
        ParseTree subtree;
        int rule = (current != null) ? table[47][current.getId()] : -1;
        ParseTree tree = new ParseTree(new NonTerminal(106, "workflow"));
        ctx.nonterminal = "workflow";
        if (current == null) {
            throw new SyntaxError(ctx.error_formatter.unexpectedEof(
                "workflow",
                nonterminal_first.get(106),
                nonterminal_rules.get(106)
            ));
        }
        if (rule == 54) {
            /* $workflow = :workflow :identifier :lbrace $_gen13 :rbrace -> Workflow( name=$1, body=$3 ) */
            ctx.rule = rules.get(54);
            LinkedHashMap<String, Integer> parameters = new LinkedHashMap<String, Integer>();
            parameters.put("name", 1);
            parameters.put("body", 3);
            tree.setAstTransformation(new AstTransformNodeCreator("Workflow", parameters));
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_WORKFLOW);
            tree.add(next);
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_IDENTIFIER);
            tree.add(next);
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_LBRACE);
            tree.add(next);
            subtree = parse__gen13(ctx);
            tree.add(subtree);
            next = expect(ctx, WdlTerminalIdentifier.TERMINAL_RBRACE);
            tree.add(next);
            return tree;
        }
        throw new SyntaxError(ctx.error_formatter.unexpectedSymbol(
            "workflow",
            current,
            nonterminal_first.get(106),
            rules.get(54)
        ));
    }
    public ParseTree parse_workflow_or_task_or_decl(List<Terminal> tokens, SyntaxErrorFormatter error_formatter) throws SyntaxError {
        ParserContext ctx = new ParserContext(new TokenStream(tokens), error_formatter);
        return parse_workflow_or_task_or_decl(ctx);
    }
    private static ParseTree parse_workflow_or_task_or_decl(ParserContext ctx) throws SyntaxError {
        Terminal current = ctx.tokens.current();
        Terminal next;
        ParseTree subtree;
        int rule = (current != null) ? table[2][current.getId()] : -1;
        ParseTree tree = new ParseTree(new NonTerminal(61, "workflow_or_task_or_decl"));
        ctx.nonterminal = "workflow_or_task_or_decl";
        if (current == null) {
            throw new SyntaxError(ctx.error_formatter.unexpectedEof(
                "workflow_or_task_or_decl",
                nonterminal_first.get(61),
                nonterminal_rules.get(61)
            ));
        }
        if (rule == 3) {
            /* $workflow_or_task_or_decl = $workflow */
            ctx.rule = rules.get(3);
            tree.setAstTransformation(new AstTransformSubstitution(0));
            subtree = parse_workflow(ctx);
            tree.add(subtree);
            return tree;
        }
        else if (rule == 4) {
            /* $workflow_or_task_or_decl = $task */
            ctx.rule = rules.get(4);
            tree.setAstTransformation(new AstTransformSubstitution(0));
            subtree = parse_task(ctx);
            tree.add(subtree);
            return tree;
        }
        else if (rule == 5) {
            /* $workflow_or_task_or_decl = $declaration */
            ctx.rule = rules.get(5);
            tree.setAstTransformation(new AstTransformSubstitution(0));
            subtree = parse_declaration(ctx);
            tree.add(subtree);
            return tree;
        }
        throw new SyntaxError(ctx.error_formatter.unexpectedSymbol(
            "workflow_or_task_or_decl",
            current,
            nonterminal_first.get(61),
            rules.get(5)
        ));
    }
    /* Section: Lexer */
    private Map<String, List<HermesRegex>> regex = null;
    private interface LexerOutput {}
    private class LexerRegexOutput implements LexerOutput {
        public WdlTerminalIdentifier terminal;
        public int group;
        public Method function;
        LexerRegexOutput(WdlTerminalIdentifier terminal, int group, Method function) {
            this.terminal = terminal;
            this.group = group;
            this.function = function;
        }
        public String toString() {
            return String.format("<LexerRegexOutput terminal=%s, group=%d, func=%s>", this.terminal, this.group, this.function);
        }
    }
    private class LexerStackPush implements LexerOutput {
        public String mode;
        LexerStackPush(String mode) {
            this.mode = mode;
        }
    }
    private class LexerAction implements LexerOutput {
        public String action;
        LexerAction(String action) {
            this.action = action;
        }
    }
    private class HermesRegex {
        public Pattern pattern;
        public List<LexerOutput> outputs;
        HermesRegex(Pattern pattern, List<LexerOutput> outputs) {
            this.pattern = pattern;
            this.outputs = outputs;
        }
        public String toString() {
            return String.format("<HermesRegex pattern=%s, outputs=%s>", this.pattern, this.outputs);
        }
    }
    private class LineColumn {
        public int line, col;
        public LineColumn(int line, int col) {
            this.line = line;
            this.col = col;
        }
        public String toString() {
            return String.format("<LineColumn: line=%d column=%d>", this.line, this.col);
        }
    }
    private class LexerContext {
        public String string;
        public String resource;
        public int line;
        public int col;
        public Stack<String> stack;
        public Object context;
        public List<Terminal> terminals;
        LexerContext(String string, String resource) {
            this.string = string;
            this.resource = resource;
            this.line = 1;
            this.col = 1;
            this.stack = new Stack<String>();
            this.stack.push("default");
            this.terminals = new ArrayList<Terminal>();
        }
        public void advance(String match) {
            LineColumn lc = advance_line_col(match, match.length());
            this.line = lc.line;
            this.col = lc.col;
            this.string = this.string.substring(match.length());
        }
        public LineColumn advance_line_col(String match, int length) {
            LineColumn lc = new LineColumn(this.line, this.col);
            for (int i = 0; i < length && i < match.length(); i++) {
                if (match.charAt(i) == '\n') {
                    lc.line += 1;
                    lc.col = 1;
                } else {
                    lc.col += 1;
                }
            }
            return lc;
        }
    }
    private void emit(LexerContext lctx, TerminalIdentifier terminal, String source_string, int line, int col) {
        lctx.terminals.add(new Terminal(terminal.id(), terminal.string(), source_string, lctx.resource, line, col));
    }
    /**
     * The default function that is called on every regex match during lexical analysis.
     * By default, this simply calls the emit() function with all of the same parameters.
     * This can be overridden in the grammar file to provide a different default action.
     *
     * @param lctx The current state of the lexical analyzer
     * @param terminal The current terminal that was matched
     * @param source_string The source code that was matched
     * @param line The line where the match happened
     * @param col The column where the match happened
     * @return void
     */
    public void default_action(LexerContext lctx, TerminalIdentifier terminal, String source_string, int line, int col) {
        emit(lctx, terminal, source_string, line, col);
    }
    /* START USER CODE */
    private class WdlContext {
    public String wf_or_task = null;
}
public Object init() {
    return new WdlContext();
}
public void workflow(LexerContext ctx, TerminalIdentifier terminal, String source_string, int line, int col) {
    ((WdlContext) ctx.context).wf_or_task = "workflow";
    default_action(ctx, terminal, source_string, line, col);
}
public void task(LexerContext ctx, TerminalIdentifier terminal, String source_string, int line, int col) {
    ((WdlContext) ctx.context).wf_or_task = "task";
    default_action(ctx, terminal, source_string, line, col);
}
public void output(LexerContext ctx, TerminalIdentifier terminal, String source_string, int line, int col) {
    WdlContext user_ctx = (WdlContext) ctx.context;
    if (user_ctx.wf_or_task != null && user_ctx.wf_or_task.equals("workflow")) {
        ctx.stack.push("wf_output");
    }
    default_action(ctx, terminal, source_string, line, col);
}
public void wdl_unescape(LexerContext ctx, TerminalIdentifier terminal, String source_string, int line, int col) {
    default_action(ctx, terminal, StringEscapeUtils.unescapeJava(source_string.substring(1, source_string.length() - 1)), line, col);
}
    /* END USER CODE */
    public List<Terminal> post_filter(List<Terminal> terminals) {
        return terminals;
    }
    public void destroy(Object context) {
        return;
    }
    private Method getFunction(String name) throws SyntaxError {
        try {
            return getClass().getMethod(
                name,
                LexerContext.class,
                TerminalIdentifier.class,
                String.class,
                int.class,
                int.class
            );
        } catch (NoSuchMethodException e) {
            throw new SyntaxError("No such method: " + name);
        }
    }
    private void lexer_init() throws SyntaxError {
        this.regex = new HashMap<String, List<HermesRegex>>();
        this.regex.put("default", Arrays.asList(new HermesRegex[] {
            new HermesRegex(
                Pattern.compile("\\s+"),
                Arrays.asList(new LexerOutput[] {
                })
            ),
            new HermesRegex(
                Pattern.compile("/\\*(.*?)\\*/", Pattern.DOTALL),
                Arrays.asList(new LexerOutput[] {
                })
            ),
            new HermesRegex(
                Pattern.compile("#.*"),
                Arrays.asList(new LexerOutput[] {
                })
            ),
            new HermesRegex(
                Pattern.compile("task(?![a-zA-Z0-9_])"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_TASK,
                        0,
                        getFunction("task")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("(call)\\s+"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_CALL,
                        1,
                        getFunction("default_action")
                    ),
                    new LexerStackPush("task_fqn"),
                })
            ),
            new HermesRegex(
                Pattern.compile("workflow(?![a-zA-Z0-9_])"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_WORKFLOW,
                        0,
                        getFunction("workflow")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("import(?![a-zA-Z0-9_])"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_IMPORT,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("input(?![a-zA-Z0-9_])"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_INPUT,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("output(?![a-zA-Z0-9_])"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_OUTPUT,
                        0,
                        getFunction("output")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("as(?![a-zA-Z0-9_])"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_AS,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("if(?![a-zA-Z0-9_])"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_IF,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("then(?![a-zA-Z0-9_])"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_THEN,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("else(?![a-zA-Z0-9_])"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_ELSE,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("while(?![a-zA-Z0-9_])"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_WHILE,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("runtime(?![a-zA-Z0-9_])"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_RUNTIME,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("scatter(?![a-zA-Z0-9_])"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_SCATTER,
                        0,
                        getFunction("default_action")
                    ),
                    new LexerStackPush("scatter"),
                })
            ),
            new HermesRegex(
                Pattern.compile("command\\s*(?=<<<)"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_RAW_COMMAND,
                        0,
                        getFunction("default_action")
                    ),
                    new LexerStackPush("raw_command2"),
                })
            ),
            new HermesRegex(
                Pattern.compile("command\\s*(?=\\{)"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_RAW_COMMAND,
                        0,
                        getFunction("default_action")
                    ),
                    new LexerStackPush("raw_command"),
                })
            ),
            new HermesRegex(
                Pattern.compile("parameter_meta(?![a-zA-Z0-9_])"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_PARAMETER_META,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("meta(?![a-zA-Z0-9_])"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_META,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("(true|false)(?![a-zA-Z0-9_])"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_BOOLEAN,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("(object)\\s*(\\{)"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_OBJECT,
                        0,
                        getFunction("default_action")
                    ),
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_LBRACE,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("(Array|Map|Object|Pair|Boolean|Int|Float|Uri|File|String)(?![a-zA-Z0-9_])(?![a-zA-Z0-9_])"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_TYPE,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("[a-zA-Z]([a-zA-Z0-9_])*"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_IDENTIFIER,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("null"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_NULL,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("\\\"(?>[^\\\\\\\"\\n]|\\\\[\\\"\\'nrbtfav\\\\?]|\\\\[0-7]{1,3}|\\\\x[0-9a-fA-F]+|\\\\[uU]([0-9a-fA-F]{4})([0-9a-fA-F]{4})?)*\\\""),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_STRING,
                        0,
                        getFunction("wdl_unescape")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("'(?>[^\\\\\\'\\n]|\\\\[\\\"\\'nrbtfav\\\\?]|\\\\[0-7]{1,3}|\\\\x[0-9a-fA-F]+|\\\\[uU]([0-9a-fA-F]{4})([0-9a-fA-F]{4})?)*'"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_STRING,
                        0,
                        getFunction("wdl_unescape")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile(":"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_COLON,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile(","),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_COMMA,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("=="),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_DOUBLE_EQUAL,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("\\|\\|"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_DOUBLE_PIPE,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("\\&\\&"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_DOUBLE_AMPERSAND,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("!="),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_NOT_EQUAL,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("="),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_EQUAL,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("\\."),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_DOT,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("\\{"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_LBRACE,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("\\}"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_RBRACE,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("\\("),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_LPAREN,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("\\)"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_RPAREN,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("\\["),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_LSQUARE,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("\\]"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_RSQUARE,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("\\+"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_PLUS,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("\\*"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_ASTERISK,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("-"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_DASH,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("/"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_SLASH,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("%"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_PERCENT,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("<="),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_LTEQ,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("<"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_LT,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile(">="),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_GTEQ,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile(">"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_GT,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("!"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_NOT,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("\\?"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_QMARK,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("-?[0-9]+\\.[0-9]+"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_FLOAT,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("[0-9]+"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_INTEGER,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
        }));
        this.regex.put("wf_output", Arrays.asList(new HermesRegex[] {
            new HermesRegex(
                Pattern.compile("\\s+"),
                Arrays.asList(new LexerOutput[] {
                })
            ),
            new HermesRegex(
                Pattern.compile("#.*"),
                Arrays.asList(new LexerOutput[] {
                })
            ),
            new HermesRegex(
                Pattern.compile("(Array|Map|Object|Pair|Boolean|Int|Float|Uri|File|String)(?![a-zA-Z0-9_])(?![a-zA-Z0-9_])"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_TYPE,
                        0,
                        getFunction("default_action")
                    ),
                    new LexerAction("pop"),
                    new LexerStackPush("wf_output_declaration"),
                })
            ),
            new HermesRegex(
                Pattern.compile("\\{"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_LBRACE,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("\\}"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_RBRACE,
                        0,
                        getFunction("default_action")
                    ),
                    new LexerAction("pop"),
                })
            ),
            new HermesRegex(
                Pattern.compile(","),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_COMMA,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("\\."),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_DOT,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("\\*"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_ASTERISK,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("[a-zA-Z]([a-zA-Z0-9_])*(\\.[a-zA-Z]([a-zA-Z0-9_])*)*"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_FQN,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
        }));
        this.regex.put("wf_output_declaration", Arrays.asList(new HermesRegex[] {
            new HermesRegex(
                Pattern.compile("\\s+"),
                Arrays.asList(new LexerOutput[] {
                })
            ),
            new HermesRegex(
                Pattern.compile("#.*"),
                Arrays.asList(new LexerOutput[] {
                })
            ),
            new HermesRegex(
                Pattern.compile("\\}"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_RBRACE,
                        0,
                        getFunction("default_action")
                    ),
                    new LexerAction("pop"),
                })
            ),
            new HermesRegex(
                Pattern.compile("\\["),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_LSQUARE,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("\\]"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_RSQUARE,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("\\+"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_PLUS,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("\\*"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_ASTERISK,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("[0-9]+"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_INTEGER,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("(true|false)(?![a-zA-Z0-9_])"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_BOOLEAN,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("if"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_IF,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("else"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_ELSE,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("then"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_THEN,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("(Array|Map|Object|Pair|Boolean|Int|Float|Uri|File|String)(?![a-zA-Z0-9_])(?![a-zA-Z0-9_])"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_TYPE,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("[a-zA-Z]([a-zA-Z0-9_])*"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_IDENTIFIER,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile(":"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_COLON,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile(","),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_COMMA,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("\\."),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_DOT,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("=="),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_DOUBLE_EQUAL,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("="),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_EQUAL,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("\\|\\|"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_DOUBLE_PIPE,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("\\&\\&"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_DOUBLE_AMPERSAND,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("!="),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_NOT_EQUAL,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("="),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_EQUAL,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("\\."),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_DOT,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("\\{"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_LBRACE,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("\\("),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_LPAREN,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("\\)"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_RPAREN,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("\\["),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_LSQUARE,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("\\]"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_RSQUARE,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("\\+"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_PLUS,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("\\*"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_ASTERISK,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("-"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_DASH,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("/"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_SLASH,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("%"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_PERCENT,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("<="),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_LTEQ,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("<"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_LT,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile(">="),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_GTEQ,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile(">"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_GT,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("!"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_NOT,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("\\?"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_QMARK,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("\\\"(?>[^\\\\\\\"\\n]|\\\\[\\\"\\'nrbtfav\\\\?]|\\\\[0-7]{1,3}|\\\\x[0-9a-fA-F]+|\\\\[uU]([0-9a-fA-F]{4})([0-9a-fA-F]{4})?)*\\\""),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_STRING,
                        0,
                        getFunction("wdl_unescape")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("'(?>[^\\\\\\'\\n]|\\\\[\\\"\\'nrbtfav\\\\?]|\\\\[0-7]{1,3}|\\\\x[0-9a-fA-F]+|\\\\[uU]([0-9a-fA-F]{4})([0-9a-fA-F]{4})?)*'"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_STRING,
                        0,
                        getFunction("wdl_unescape")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("-?[0-9]+\\.[0-9]+"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_FLOAT,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("[0-9]+"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_INTEGER,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
        }));
        this.regex.put("task_fqn", Arrays.asList(new HermesRegex[] {
            new HermesRegex(
                Pattern.compile("\\s+"),
                Arrays.asList(new LexerOutput[] {
                })
            ),
            new HermesRegex(
                Pattern.compile("[a-zA-Z]([a-zA-Z0-9_])*(\\.[a-zA-Z]([a-zA-Z0-9_])*)*"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_FQN,
                        0,
                        getFunction("default_action")
                    ),
                    new LexerAction("pop"),
                })
            ),
        }));
        this.regex.put("scatter", Arrays.asList(new HermesRegex[] {
            new HermesRegex(
                Pattern.compile("\\s+"),
                Arrays.asList(new LexerOutput[] {
                })
            ),
            new HermesRegex(
                Pattern.compile("\\("),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_LPAREN,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("in(?![a-zA-Z0-9_])"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_IN,
                        0,
                        getFunction("default_action")
                    ),
                    new LexerAction("pop"),
                })
            ),
            new HermesRegex(
                Pattern.compile("[a-zA-Z]([a-zA-Z0-9_])*"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_IDENTIFIER,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
        }));
        this.regex.put("raw_command", Arrays.asList(new HermesRegex[] {
            new HermesRegex(
                Pattern.compile("\\{"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_RAW_CMD_START,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("\\}"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_RAW_CMD_END,
                        0,
                        getFunction("default_action")
                    ),
                    new LexerAction("pop"),
                })
            ),
            new HermesRegex(
                Pattern.compile("\\$\\{"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_CMD_PARAM_START,
                        0,
                        getFunction("default_action")
                    ),
                    new LexerStackPush("cmd_param"),
                })
            ),
            new HermesRegex(
                Pattern.compile("(.*?)(?=\\$\\{|\\})", Pattern.DOTALL),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_CMD_PART,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
        }));
        this.regex.put("raw_command2", Arrays.asList(new HermesRegex[] {
            new HermesRegex(
                Pattern.compile("<<<"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_RAW_CMD_START,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile(">>>"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_RAW_CMD_END,
                        0,
                        getFunction("default_action")
                    ),
                    new LexerAction("pop"),
                })
            ),
            new HermesRegex(
                Pattern.compile("\\$\\{"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_CMD_PARAM_START,
                        0,
                        getFunction("default_action")
                    ),
                    new LexerStackPush("cmd_param"),
                })
            ),
            new HermesRegex(
                Pattern.compile("(.*?)(?=\\$\\{|>>>)", Pattern.DOTALL),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_CMD_PART,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
        }));
        this.regex.put("cmd_param", Arrays.asList(new HermesRegex[] {
            new HermesRegex(
                Pattern.compile("\\s+"),
                Arrays.asList(new LexerOutput[] {
                })
            ),
            new HermesRegex(
                Pattern.compile("\\}"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_CMD_PARAM_END,
                        0,
                        getFunction("default_action")
                    ),
                    new LexerAction("pop"),
                })
            ),
            new HermesRegex(
                Pattern.compile("\\["),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_LSQUARE,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("\\]"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_RSQUARE,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("="),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_EQUAL,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("\\+"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_PLUS,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("\\*"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_ASTERISK,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("[0-9]+"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_INTEGER,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("if"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_IF,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("else"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_ELSE,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("then"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_THEN,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("[a-zA-Z]([a-zA-Z0-9_])*(?=\\s*=)"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_CMD_ATTR_HINT,
                        -1,
                        getFunction("default_action")
                    ),
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_IDENTIFIER,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("(true|false)(?![a-zA-Z0-9_])"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_BOOLEAN,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("(Array|Map|Object|Pair|Boolean|Int|Float|Uri|File|String)(?![a-zA-Z0-9_])(?![a-zA-Z0-9_])"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_TYPE,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("[a-zA-Z]([a-zA-Z0-9_])*"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_IDENTIFIER,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile(":"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_COLON,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile(","),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_COMMA,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("\\."),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_DOT,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("=="),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_DOUBLE_EQUAL,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("\\|\\|"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_DOUBLE_PIPE,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("\\&\\&"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_DOUBLE_AMPERSAND,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("!="),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_NOT_EQUAL,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("="),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_EQUAL,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("\\."),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_DOT,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("\\{"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_LBRACE,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("\\("),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_LPAREN,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("\\)"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_RPAREN,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("\\["),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_LSQUARE,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("\\]"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_RSQUARE,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("\\+"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_PLUS,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("\\*"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_ASTERISK,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("-"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_DASH,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("/"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_SLASH,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("%"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_PERCENT,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("<="),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_LTEQ,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("<"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_LT,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile(">="),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_GTEQ,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile(">"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_GT,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("!"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_NOT,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("\\\"(?>[^\\\\\\\"\\n]|\\\\[\\\"\\'nrbtfav\\\\?]|\\\\[0-7]{1,3}|\\\\x[0-9a-fA-F]+|\\\\[uU]([0-9a-fA-F]{4})([0-9a-fA-F]{4})?)*\\\""),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_STRING,
                        0,
                        getFunction("wdl_unescape")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("'(?>[^\\\\\\'\\n]|\\\\[\\\"\\'nrbtfav\\\\?]|\\\\[0-7]{1,3}|\\\\x[0-9a-fA-F]+|\\\\[uU]([0-9a-fA-F]{4})([0-9a-fA-F]{4})?)*'"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_STRING,
                        0,
                        getFunction("wdl_unescape")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("-?[0-9]+\\.[0-9]+"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_FLOAT,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
            new HermesRegex(
                Pattern.compile("[0-9]+"),
                Arrays.asList(new LexerOutput[] {
                    new LexerRegexOutput(
                        WdlTerminalIdentifier.TERMINAL_INTEGER,
                        0,
                        getFunction("default_action")
                    ),
                })
            ),
        }));
    }
    private void unrecognized_token(String string, int line, int col) throws SyntaxError {
        String[] a = string.split("\n");
        String bad_line = string.split("\n")[line-1];
        StringBuffer spaces = new StringBuffer();
        for (int i = 0; i < col-1; i++) {
          spaces.append(' ');
        }
        String message = String.format(
            "Unrecognized token on line %d, column %d:\n\n%s\n%s^",
            line, col, bad_line, spaces
        );
        throw new SyntaxError(message);
    }
    private int next(LexerContext lctx) throws SyntaxError {
        String mode = lctx.stack.peek();
        for (int i = 0; i < this.regex.get(mode).size(); i++) {
            HermesRegex regex = this.regex.get(mode).get(i);
            Matcher matcher = regex.pattern.matcher(lctx.string);
            if (matcher.lookingAt()) {
                for (LexerOutput output : regex.outputs) {
                    if (output instanceof LexerStackPush) {
                        lctx.stack.push(((LexerStackPush) output).mode);
                    } else if (output instanceof LexerAction) {
                        LexerAction action = (LexerAction) output;
                        if (!action.action.equals("pop")) {
                            throw new SyntaxError("Invalid action");
                        }
                        if (action.action.equals("pop")) {
                            if (lctx.stack.empty()) {
                                throw new SyntaxError("Stack empty, cannot pop");
                            }
                            lctx.stack.pop();
                        }
                    } else if (output instanceof LexerRegexOutput) {
                        LexerRegexOutput regex_output = (LexerRegexOutput) output;
                        int group_line = lctx.line;
                        int group_col = lctx.col;
                        if (regex_output.group > 0) {
                            LineColumn lc = lctx.advance_line_col(matcher.group(0), matcher.start(regex_output.group));
                            group_line = lc.line;
                            group_col = lc.col;
                        }
                        try {
                            String source_string = (regex_output.group >= 0) ? matcher.group(regex_output.group) : "";
                            regex_output.function.invoke(
                                this,
                                lctx,
                                regex_output.terminal,
                                source_string,
                                group_line,
                                group_col
                            );
                        } catch (Exception e) {
                            e.printStackTrace();
                            throw new SyntaxError("Invalid method: " + regex_output.function);
                        }
                    }
                }
                lctx.advance(matcher.group(0));
                return matcher.group(0).length();
            }
        }
        return 0;
    }
    /**
     * Lexically analyze WDL source code, return a sequence of tokens.  Output of this
     * method should be used to construct a TerminalStream and then pass that to parse()
     *
     * @param string The WDL source code to analyze
     * @param resource A descriptor of where this code came from (usually a file path)
     * @return List of Terminal objects.
     * @throws SyntaxError If part of the source code could not lexically analyzed
     */
    public List<Terminal> lex(String string, String resource) throws SyntaxError {
        LexerContext lctx = new LexerContext(string, resource);
        Object context = this.init();
        lctx.context = context;
        String string_copy = new String(string);
        if (this.regex == null) {
            lexer_init();
        }
        while (lctx.string.length() > 0) {
            int match_length = this.next(lctx);
            if (match_length == 0) {
                this.unrecognized_token(string_copy, lctx.line, lctx.col);
            }
        }
        this.destroy(context);
        List<Terminal> filtered = post_filter(lctx.terminals);
        return filtered;
    }
    /* Section: Main */
}
