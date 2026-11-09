package hu.pihokker.mesevetito;

import java.text.Collator;
import java.util.Locale;

/** Természetes sorrend: "Oldal_2" a "Oldal_10" elé kerül, ékezetek magyar szerint. */
final class NaturalOrder {

    private static final Collator COLLATOR = Collator.getInstance(new Locale("hu", "HU"));

    static {
        COLLATOR.setStrength(Collator.SECONDARY);
    }

    private NaturalOrder() {
    }

    static int compare(String a, String b) {
        int i = 0, j = 0;
        while (i < a.length() && j < b.length()) {
            char ca = a.charAt(i), cb = b.charAt(j);
            if (Character.isDigit(ca) && Character.isDigit(cb)) {
                int si = i, sj = j;
                while (i < a.length() && Character.isDigit(a.charAt(i))) i++;
                while (j < b.length() && Character.isDigit(b.charAt(j))) j++;
                String na = trimZeros(a.substring(si, i));
                String nb = trimZeros(b.substring(sj, j));
                if (na.length() != nb.length()) return na.length() - nb.length();
                int c = na.compareTo(nb);
                if (c != 0) return c;
            } else {
                int si = i, sj = j;
                while (i < a.length() && !Character.isDigit(a.charAt(i))) i++;
                while (j < b.length() && !Character.isDigit(b.charAt(j))) j++;
                int c = COLLATOR.compare(a.substring(si, i), b.substring(sj, j));
                if (c != 0) return c;
            }
        }
        return (a.length() - i) - (b.length() - j);
    }

    private static String trimZeros(String s) {
        int k = 0;
        while (k < s.length() - 1 && s.charAt(k) == '0') k++;
        return s.substring(k);
    }
}
