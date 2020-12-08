package main;

import java.io.IOException;

public class IBMWatson {

    public static String dataPath = "src/resources";

    public static void main(String[] args) {
        System.out.println("This is the start of the project");

        IndexEngine indexEngine = new IndexEngine("src/resources");

        try {
            indexEngine.parseWikiData();
        } catch (IOException ex) {
            System.out.println("EXCEPTION OCCURRED");
            System.exit(0);
        }
    }


}
