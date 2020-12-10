package main;

import java.io.IOException;
import java.util.Scanner;

public class Watson {

    public static String dataPath = "src/resources/";

    public static void main(String[] args) {
        System.out.println("Welcome to Trey's CSC483 IBM Watson Project!");
        System.out.println("Please choose index and query options from the menus below.\n");

        int[] options = menuOptions();
        //System.out.println(options);

        Indexer indexer = new Indexer(dataPath, options[0]);
        try {
            indexer.parseWikis();
        } catch (IOException ex) {
            System.out.println("EXCEPTION OCCURRED");
            System.exit(0);
        }

        System.out.println("Wiki Documents Parsed.\n");
        QueryEngine qe = new QueryEngine(options[0], options[1], indexer);
        qe.sherlockinator();
    }

    public static int[] menuOptions() {
        Scanner in = new Scanner(System.in);
        int[] options = new int[2];

        System.out.println("These are the indexing options available:");
        System.out.println("(1) Default (Neither lemmatization nor stemming)");
        System.out.println("(2) Lemmatization\n(3) Stemming\nPlease type a number: ");

        String userIn = in.nextLine();
        if (userIn.length() > 1) {
            System.out.println("Invalid choice");
        }
        options[0] = Integer.parseInt(userIn);

        System.out.println("\nThese are the querying options available:");
        System.out.println("(1) BM25 (Default)\n(2) Boolean\n(3) TF-IDF");
        System.out.println("(4) Jelinek-Mercer\nPlease type a number: ");

        userIn = in.nextLine();
        if (userIn.length() > 1) {
            System.out.println("Invalid choice");
        }
        options[1] = Integer.parseInt(userIn);

        return options;
    }



}
