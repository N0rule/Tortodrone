package org.tortodrone.web;
import java.io.IOException;
import java.util.*;
import org.tortodrone.resources.Json;
/** Serialization is limited to explicitly selected DTOs, records and primitive collections. */
public final class WebJson {
    private WebJson(){}
    public static String encode(Object value)throws IOException{return Json.stringify(data(value));}
    private static Object data(Object v)throws IOException {
        if(v==null||v instanceof String||v instanceof Number||v instanceof Boolean)return v;
        if(v instanceof Enum<?>||v instanceof java.time.temporal.TemporalAccessor)return v.toString();
        if(v instanceof Collection<?> c){var a=new ArrayList<>();for(var e:c)a.add(data(e));return a;}
        if(v instanceof Map<?,?> m){var a=new LinkedHashMap<String,Object>();for(var e:m.entrySet())a.put(e.getKey().toString(),data(e.getValue()));return a;}
        if(v.getClass().isRecord()){
            var m=new LinkedHashMap<String,Object>();
            try{for(var c:v.getClass().getRecordComponents())m.put(c.getName(),data(c.getAccessor().invoke(v)));}
            catch(ReflectiveOperationException e){throw new IOException("Cannot serialize response",e);}return m;
        }
        throw new IOException("Unsupported response type");
    }
}
