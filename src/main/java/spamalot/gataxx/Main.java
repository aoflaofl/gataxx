package spamalot.gataxx;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import spamalot.gataxx.uai.UaiEngine;

/** Entry point for the gataxx engine. Speaks UAI (Universal Ataxx Interface) on stdin/stdout. */
public final class Main {
    private Main() {}

    public static void main(String[] args) {
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
        PrintWriter out = new PrintWriter(System.out, true);
        new UaiEngine(in, out).run();
        out.flush();
    }

    public static String version() {
        return "1.5.0";
    }
}
