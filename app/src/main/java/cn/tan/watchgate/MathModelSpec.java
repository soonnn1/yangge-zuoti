package cn.tan.watchgate;

import org.json.JSONObject;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 对千问提供的模型参数做范围和题干数值检查，不执行生成的代码。 */
public final class MathModelSpec {
    private MathModelSpec() { }

    public static void validate(JSONObject model,Question question) throws Exception {
        if(!"数学".equals(question.subject)||model.optBoolean("unavailable")) throw new IllegalArgumentException("这道题暂不支持直观模型");
        String type=model.optString("type","");
        if(type.equals("fraction")) {
            int total=integer(model,"parts",2,24),selected=integer(model,"selected",1,total);
            given(question,total); given(question,selected);
        } else if(type.equals("rectangle")) {
            int width=integer(model,"width",1,16),height=integer(model,"height",1,16);
            given(question,width); given(question,height);
        } else if(type.equals("groups")) {
            int groups=integer(model,"groups",1,10),each=integer(model,"each",1,12);
            given(question,groups); given(question,each);
        } else if(type.equals("number_line")) {
            int start=integer(model,"start",-50,50),distance=integer(model,"distance",1,20);
            integer(model,"direction",-1,1); if(model.optInt("direction")==0) throw new IllegalArgumentException("数轴方向无效");
            given(question,start); given(question,distance);
        } else if(type.equals("linear")) {
            int variables=0;
            for(String key:new String[]{"a","b","c"}) {
                Object v=model.opt(key);
                if(v instanceof String && v.equals("p")) { variables++; continue; }
                if(!(v instanceof Number)) throw new IllegalArgumentException("直线参数无效");
                double number=((Number)v).doubleValue(); if(!Double.isFinite(number)||Math.abs(number)>100) throw new IllegalArgumentException("直线参数超出范围");
                // 0 和 ±1 可以是方程里省略的系数，其余数值必须在题干中出现。
                if(number!=0&&Math.abs(number)!=1) given(question,number);
            }
            if(variables!=1) throw new IllegalArgumentException("直线需有一个可调参数");
            double x=number(model,"pointX",-50,50),y=number(model,"pointY",-50,50);
            given(question,x); given(question,y);
            String name=model.optString("parameterName","");
            if(!name.matches("[A-Za-z]")||!question.title.contains(name)) throw new IllegalArgumentException("可调参数未在题干中找到");
        } else throw new IllegalArgumentException("这道题暂不支持直观模型");
    }

    private static int integer(JSONObject value,String key,int min,int max) throws Exception {
        double n=number(value,key,min,max); if(n!=Math.rint(n)) throw new IllegalArgumentException("模型需要整数数据"); return (int)n;
    }
    private static double number(JSONObject value,String key,double min,double max) throws Exception {
        Object v=value.opt(key); if(!(v instanceof Number)) throw new IllegalArgumentException("模型数据不完整");
        double n=((Number)v).doubleValue();
        if(!Double.isFinite(n)||n<min||n>max) throw new IllegalArgumentException("模型数据超出范围"); return n;
    }
    private static void given(Question question,double target) throws Exception {
        Matcher m=Pattern.compile("(?<![0-9.])[-+]?[0-9]+(?:\\.[0-9]+)?(?![0-9.])").matcher(MathText.format(question.title));
        while(m.find()) try { if(Math.abs(Math.abs(Double.parseDouble(m.group()))-Math.abs(target))<1e-9) return; } catch(NumberFormatException ignored) { }
        throw new IllegalArgumentException("模型使用了题干中未明确给出的数值");
    }
}
