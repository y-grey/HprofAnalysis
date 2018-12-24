package yph.hprofanalysis;

import com.squareup.leakcanary.HeapAnalyzer;

public class Main {

    public static void main(String[] args) {
        if (args.length == 0)
            System.err.println("Parameter error");
        boolean findLeak = (args.length > 1 && args[1].equals("findLeak")) || (args.length > 2 && args[2].equals("findLeak"));
        boolean findBitmap = (args.length > 1 && args[1].equals("findBitmap")) || (args.length > 2 && args[2].equals("findBitmap"));
        new HeapAnalyzer().checkForLeak(args[0], findLeak, findBitmap);
    }
}
