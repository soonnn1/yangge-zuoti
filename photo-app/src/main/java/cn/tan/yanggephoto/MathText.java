package cn.tan.yanggephoto;

/** 把常见公式记号转成可读文本；只影响显示，不改动题库或答案。 */
public final class MathText {
    private MathText() { }

    public static String format(String value) {
        return value==null?"":render(value,0);
    }

    private static String render(String source,int depth) {
        if(depth>12) return source;
        StringBuilder out=new StringBuilder(); boolean mathDelimiter=false;
        for(int i=0;i<source.length();) {
            char c=source.charAt(i);
            if(c=='$'&&(mathDelimiter||source.indexOf('$',i+1)>=0)) { mathDelimiter=!mathDelimiter; i++; continue; }
            if(c=='_'&&(i==0||!Character.isLetterOrDigit(source.charAt(i-1))||i+1>=source.length()||source.charAt(i+1)=='_')) { out.append(c); i++; continue; }
            if(c=='^'||c=='_') {
                boolean upper=c=='^'; int start=i+1;
                Group group=group(source,start);
                String raw;
                if(group!=null) { raw=render(group.value,depth+1); i=group.end; }
                else if(start<source.length()) { raw=String.valueOf(source.charAt(start)); i=start+1; }
                else { out.append(c); i++; continue; }
                String converted=script(raw,upper);
                if(converted!=null) out.append(converted);
                else out.append(c).append('(').append(raw).append(')');
                continue;
            }
            if(c!='\\') { out.append(c); i++; continue; }
            if(i+1>=source.length()) { out.append(c); i++; continue; }
            char next=source.charAt(i+1);
            if(next=='\\') { out.append("；"); i+=2; continue; }
            if(next=='('||next==')'||next=='['||next==']') { i+=2; continue; }
            if(!Character.isLetter(next)) {
                if(next=='{'||next=='}'||next=='%'||next=='_') out.append(next);
                else if(next==','||next==';'||next==' '||next=='!') out.append(' ');
                else out.append('\\').append(next);
                i+=2; continue;
            }
            int end=i+1; while(end<source.length()&&Character.isLetter(source.charAt(end))) end++;
            String command=source.substring(i+1,end);
            if(command.equals("begin")) {
                Group name=group(source,end);
                if(name!=null && (name.value.equals("cases")||name.value.equals("aligned"))) {
                    String closing="\\end{"+name.value+"}"; int stop=source.indexOf(closing,name.end);
                    if(stop>=0) {
                        String inner=source.substring(name.end,stop).replace("&","");
                        if(name.value.equals("cases")) out.append('｛');
                        out.append(render(inner,depth+1).trim());
                        if(name.value.equals("cases")) out.append('｝');
                        i=stop+closing.length(); continue;
                    }
                }
            }
            if(command.equals("frac")||command.equals("dfrac")||command.equals("tfrac")) {
                Group numerator=group(source,end),denominator=numerator==null?null:group(source,numerator.end);
                if(denominator!=null) {
                    out.append('(').append(fractionPart(render(numerator.value,depth+1))).append('/')
                        .append(fractionPart(render(denominator.value,depth+1))).append(')');
                    i=denominator.end; continue;
                }
            }
            if(command.equals("sqrt")||command.equals("text")||command.equals("mathrm")||command.equals("mathbf")||command.equals("operatorname")) {
                Group part=group(source,end);
                if(part!=null) {
                    String content=render(part.value,depth+1);
                    if(command.equals("sqrt")) out.append('√').append(fractionPart(content)); else out.append(content);
                    i=part.end; continue;
                }
            }
            if(command.equals("left")||command.equals("right")) {
                i=end; if(i<source.length()&&source.charAt(i)=='.') i++; continue;
            }
            String symbol=symbol(command);
            if(symbol!=null) out.append(symbol); else out.append('\\').append(command);
            i=end;
        }
        return out.toString();
    }

    private static String fractionPart(String value) {
        String text=value.trim();
        return text.matches("[\\p{L}\\p{N}.]+")?text:"("+text+")";
    }

    private static String script(String value,boolean upper) {
        String from="0123456789+-=()",to=upper?"⁰¹²³⁴⁵⁶⁷⁸⁹⁺⁻⁼⁽⁾":"₀₁₂₃₄₅₆₇₈₉₊₋₌₍₎";
        StringBuilder result=new StringBuilder();
        for(int i=0;i<value.length();i++) { int index=from.indexOf(value.charAt(i)); if(index<0) return null; result.append(to.charAt(index)); }
        return result.toString();
    }

    private static String symbol(String command) {
        switch(command) {
            case "times": return "×"; case "div": return "÷"; case "cdot": return "·";
            case "le": case "leq": return "≤"; case "ge": case "geq": return "≥";
            case "ne": case "neq": return "≠"; case "approx": return "≈"; case "pm": return "±";
            case "infty": return "∞"; case "pi": return "π"; case "alpha": return "α";
            case "beta": return "β"; case "theta": return "θ"; case "circ": return "°";
            case "rightarrow": case "to": return "→"; case "quad": case "qquad": return " ";
            case "displaystyle": return "";
            default: return null;
        }
    }

    private static Group group(String source,int at) {
        while(at<source.length()&&Character.isWhitespace(source.charAt(at))) at++;
        if(at>=source.length()||source.charAt(at)!='{') return null;
        int depth=1;
        for(int i=at+1;i<source.length();i++) {
            char c=source.charAt(i);
            if(c=='{'&&(i==0||source.charAt(i-1)!='\\')) depth++;
            else if(c=='}'&&(i==0||source.charAt(i-1)!='\\')&&--depth==0) return new Group(source.substring(at+1,i),i+1);
        }
        return null;
    }

    private static final class Group {
        final String value; final int end;
        Group(String value,int end) { this.value=value; this.end=end; }
    }
}
