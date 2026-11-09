package main;
import edu.stanford.nlp.simple.Sentence;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
/** Read-only title/category scan to localize tokenless source fields. */
public final class EmptyInputProbe {
    public static void main(String[] args) throws Exception {
        try(Indexer parser=new Indexer("unused-probe-index",1);var files=Files.list(Paths.get(args[0]))) {
            for(Path file:files.filter(p->p.toString().endsWith(".txt")).sorted().toList()) {
                int line=0;String title="";
                try(var input=Files.newBufferedReader(file,StandardCharsets.UTF_8)) {
                    String raw;
                    while((raw=input.readLine())!=null) {
                        line++;String text=raw.trim(),field=null;
                        if(parser.isTitle(text)){title=parser.parseTitle(text);field=title;}
                        else if(parser.isCategory(text))field=parser.parseCategory(text);
                        if(field!=null && !field.isEmpty())try{new Sentence(field.toLowerCase(java.util.Locale.ROOT)).words();}
                        catch(RuntimeException error){System.out.println(file.getFileName()+":"+line+" title="+title+" field="+field+" chars="+field.codePoints().mapToObj(Integer::toHexString).toList());throw error;}
                    }
                }
            }
        }
    }
}
