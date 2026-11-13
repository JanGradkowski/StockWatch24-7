package org.example.stockwatch247.i18n;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Polish names for the officer titles insiders report on SEC Form 4. Filers write them freely ("EVP, Chief
 * Financial Officer", "Chair, President & CEO", "CHIEF TECHNOLOGY OFFICER"), so a title is split into its
 * parts and translated only when every part is a known role; otherwise it is shown as filed, never half-translated.
 */
final class RoleTitles {
    private static final Map<String, String> ROLES = roles();
    /** Separators between the parts of a title: commas, "&", "and", slashes and dashes. */
    private static final Pattern SEPARATOR = Pattern.compile("\\s*,\\s*|\\s*&\\s*|\\s+and\\s+|\\s*/\\s*|\\s+[-–]\\s+");

    private RoleTitles() {
    }

    /** The title in Polish, or null when any part of it is not a known role. */
    static String polish(String title) {
        if (title == null || title.isBlank()) return null;
        String text = title.strip().replaceAll("\\s+", " ");
        String whole = ROLES.get(key(text));
        if (whole != null) return whole;
        List<String> parts = new ArrayList<>();
        List<String> joins = new ArrayList<>();
        Matcher separator = SEPARATOR.matcher(text);
        int start = 0;
        while (separator.find()) {
            parts.add(text.substring(start, separator.start()));
            joins.add(separator.group().strip());
            start = separator.end();
        }
        parts.add(text.substring(start));
        if (parts.size() < 2) return null;
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < parts.size(); index++) {
            String role = ROLES.get(key(parts.get(index)));
            if (role == null) return null;
            if (index > 0) {
                String join = joins.get(index - 1);
                result.append(join.equals("&") || join.equalsIgnoreCase("and") ? " i " : join.equals("/") ? " / " : ", ");
                role = Character.toLowerCase(role.charAt(0)) + role.substring(1);
            }
            result.append(role);
        }
        return result.toString();
    }

    private static String key(String part) {
        return part.strip().replace(".", "").toLowerCase(Locale.ROOT);
    }

    private static Map<String, String> roles() {
        Map<String, String> roles = new java.util.HashMap<>();
        java.util.function.BiConsumer<String, String[]> add = (polish, english) -> {
            for (String name : english) roles.put(name, polish);
        };
        add.accept("Członek rady dyrektorów", new String[]{"director", "board member", "member of the board"});
        add.accept("Członek kadry zarządzającej", new String[]{"officer"});
        add.accept("Właściciel ponad 10% akcji", new String[]{"10% owner"});
        add.accept("Prezes zarządu (CEO)", new String[]{"chief executive officer", "ceo"});
        add.accept("Dyrektor finansowy (CFO)", new String[]{"chief financial officer", "cfo"});
        add.accept("Dyrektor operacyjny (COO)", new String[]{"chief operating officer", "coo"});
        add.accept("Dyrektor ds. technologii (CTO)", new String[]{"chief technology officer", "cto"});
        add.accept("Dyrektor ds. informatyki (CIO)", new String[]{"chief information officer", "cio"});
        add.accept("Dyrektor ds. marketingu (CMO)", new String[]{"chief marketing officer", "cmo"});
        add.accept("Dyrektor ds. prawnych", new String[]{"chief legal officer", "clo"});
        add.accept("Dyrektor administracyjny", new String[]{"chief administrative officer"});
        add.accept("Dyrektor ds. księgowości", new String[]{"chief accounting officer", "principal accounting officer"});
        add.accept("Dyrektor ds. rozwoju biznesu", new String[]{"chief business officer"});
        add.accept("Dyrektor ds. produktu", new String[]{"chief product officer"});
        add.accept("Dyrektor ds. usług", new String[]{"chief services officer"});
        add.accept("Dyrektor ds. klientów", new String[]{"chief customer officer"});
        add.accept("Dyrektor ds. personalnych", new String[]{"chief human resources officer", "chief people officer", "head of human resources"});
        add.accept("Główny radca prawny", new String[]{"general counsel", "gc"});
        add.accept("Prezes", new String[]{"president", "pres"});
        // "President and CEO" is one role, the head of the management board.
        add.accept("Prezes zarządu (CEO)", new String[]{"president & ceo", "president and ceo", "president, ceo"});
        add.accept("Przewodniczący rady dyrektorów i prezes zarządu (CEO)", new String[]{"chair, president & ceo", "chairman, president & ceo",
                "chairman, president and ceo", "chair, president and ceo"});
        add.accept("Przewodniczący rady dyrektorów", new String[]{"chair", "chairman", "chairwoman", "chair of the board", "chairman of the board"});
        add.accept("Przewodniczący wykonawczy rady dyrektorów", new String[]{"executive chair", "executive chairman"});
        add.accept("Wiceprzewodniczący rady dyrektorów", new String[]{"vice chair", "vice chairman"});
        add.accept("Wiceprezes wykonawczy", new String[]{"executive vice president", "evp"});
        add.accept("Starszy wiceprezes", new String[]{"senior vice president", "svp"});
        add.accept("Wiceprezes", new String[]{"vice president", "vp"});
        add.accept("Skarbnik", new String[]{"treasurer"});
        add.accept("Sekretarz", new String[]{"secretary", "corporate secretary"});
        add.accept("Kontroler finansowy", new String[]{"controller", "corporate controller"});
        add.accept("Inna funkcja", new String[]{"other"});
        return Map.copyOf(roles);
    }
}
