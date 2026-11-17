package app.orrery.core.understand;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Content words for full-text recall: the message minus time phrases, stopwords and
 * conversational filler.
 *
 * <pre>"Last Tuesday I asked you about Postgres hosting" → [postgres, hosting]</pre>
 */
public final class TopicTerms {

    private static final Set<String> STOPWORDS = Arrays.stream((
            "a about above after again against all also am an and any anyone anything are aren't around as at be because been before being below "
            + "between both but by can can't cannot could couldn't did didn't do does doesn't doing don't down during each either else even ever every "
            + "few for from further get gets got had hadn't has hasn't have haven't having he her here hers herself him himself his how however i i'd "
            + "i'll i'm i've if in into is isn't it it's its itself just let let's like lot lots me might more most much must my myself need no nor not "
            + "now of off on once one only or other others our ours ourselves out over own per please quite rather really same shall she should so some "
            + "something such than that that's the their theirs them themselves then there there's these they they'd they're thing things this those "
            + "though through thus to too under until up upon us very via was wasn't way we we'd we'll we're we've were weren't what what's whatever when "
            + "where whether which while who who's whom whose why will with within without won't would wouldn't yes yet you you'd you'll you're you've "
            + "your yours yourself yourselves "
            + "ask asked asking tell told telling say said saying discuss discussed discussing talk talked talking chat chatted mention mentioned "
            + "remember recall question questions answer answers answered reply replied response still correct true accurate right valid changed change "
            + "changes changing since ago last earlier previous previously recently today yesterday tonight tomorrow week weeks month months year years "
            + "day days morning afternoon evening night time times monday tuesday wednesday thursday friday saturday sunday january february march april "
            + "may june july august september october november december jan feb mar apr jun jul aug sep sept oct nov dec hey hi hello thanks thank "
            + "check recheck verify update updates updated new latest current currently big shipped ship launched released anything everything "
            + "someone somebody okay ok sure good great kind sort bit maybe probably actually already again ones two three four five six seven "
            + "eight nine ten shortlisted shortlist looked compared picked chose went based")
            .split("\\s+")).collect(Collectors.toUnmodifiableSet());

    private TopicTerms() {}

    public static List<String> extract(String text, List<TimeRef> refs) {
        if (text == null || text.isBlank()) return List.of();
        char[] chars = text.toCharArray();
        for (TimeRef ref : refs) {
            int end = Math.min(chars.length, ref.index() + ref.phrase().length());
            for (int i = Math.max(0, ref.index()); i < end; i++) chars[i] = ' ';
        }
        String blanked = Normalizer.normalize(new String(chars), Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replace('’', '\'');
        List<String> out = new ArrayList<>();
        for (String raw : blanked.split("[^\\p{L}\\p{N}'+#]+")) {
            String token = raw.replaceAll("^'+|'+$", "").replaceAll("'s$", "");
            if (token.length() < 2 || STOPWORDS.contains(token)) continue;
            if (token.matches("\\d+(st|nd|rd|th)?")) continue;
            if (!out.contains(token)) out.add(token);
            if (out.size() >= 10) break;
        }
        return List.copyOf(out);
    }
}
