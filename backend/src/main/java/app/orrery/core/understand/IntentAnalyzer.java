package app.orrery.core.understand;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Cheap, deterministic reading of a message, run before the model is called.
 *
 * <p>These are hints, not verdicts: the model still sees the full message and decides
 * how to answer. They exist so memory is always loaded when a message needs it, without
 * spending a model round-trip just to decide that.
 */
public final class IntentAnalyzer {

    private static final int F = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;

    private static final List<Pattern> RECALL = List.of(
            Pattern.compile("\\b(i|we)\\s+(?:had\\s+|have\\s+|just\\s+|already\\s+|briefly\\s+)?(asked|told|mentioned|said|discussed|talked|chatted|spoke|looked|compared|shortlisted|short-listed|listed|picked|chose|decided|planned|reviewed|researched|covered|went over|went through|searched|explored|evaluated|narrowed|settled|agreed|figured)\\b", F),
            Pattern.compile("\\byou\\s+(?:had\\s+|have\\s+|just\\s+)?(said|told|mentioned|recommended|suggested|answered|replied|found|gave|listed|shortlisted|picked|showed|explained|wrote|claimed|estimated|quoted|compared|summari[sz]ed)\\b", F),
            Pattern.compile("\\b(did|have|had)\\s+(i|we|you)\\s+(ever\\s+)?(ask|asked|talk|talked|discuss|discussed|mention|mentioned|tell|told|look|looked|compare|compared|cover|covered|say|said|recommend|recommended)\\b", F),
            Pattern.compile("\\bwhat\\s+(did|have)\\s+(i|we)\\b", F),
            Pattern.compile("\\b(last\\s+time|previously|earlier\\s+(today|this\\s+week|conversation|chat)|our\\s+(last\\s+|previous\\s+|earlier\\s+)?(chat|conversation|discussion|talk)|as\\s+(we|you)\\s+(discussed|said|mentioned)|you\\s+remember|do\\s+you\\s+(remember|recall)|remember\\s+(when|what|the|that|our|which)|from\\s+(our|the)\\s+(last|previous|earlier)\\s+(chat|conversation|discussion))\\b", F),
            Pattern.compile("\\b(the|those|these|that)\\s+(\\w+\\s+)?(ones|tools|options|products|vendors|companies|providers|services|apps|models|plans|items|picks|list|shortlist|recommendations?)\\s+(we|you|i)\\b", F),
            Pattern.compile("\\bmy\\s+(last\\s+|previous\\s+|earlier\\s+)?(question|questions)\\b", F),
            Pattern.compile("\\byour\\s+(last\\s+|previous\\s+|earlier\\s+|original\\s+)?(answer|answers|reply|response|recommendation|suggestion|list|advice)\\b", F));

    private static final List<Pattern> VERIFY = List.of(
            Pattern.compile("\\bstill\\s+(correct|true|accurate|valid|right|the\\s+case|current|up[- ]to[- ]date|available|free|the\\s+(best|cheapest|same|right)|good|relevant|working|priced)\\b", F),
            Pattern.compile("\\b(is|are|was|were)\\s+(that|this|it|they|those|these|your\\s+\\w+)\\s+still\\b", F),
            Pattern.compile("\\b(changed?|updated|shipped|launched|released|announced|raised|lowered|cut|dropped|increased|decreased|deprecated|discontinued|acquired)\\b[^.?!]*\\bsince\\b", F),
            Pattern.compile("\\bsince\\b[^.?!]*\\b(changed?|updated|shipped|launched|released|announced|raised|lowered|dropped|increased|decreased)\\b", F),
            Pattern.compile("\\b(has|have|did)\\s+(anything|any\\s+of\\s+(them|these|those)|any|something|it|they|the\\s+\\w+|pricing|prices?)\\b[^.?!]{0,40}\\b(change|changed|moved|updated|shipped|launched|released)\\b", F),
            Pattern.compile("\\b(any|anything)\\s+(new|changes?|updates?|news)\\s+(since|on|about|with|for)\\b", F),
            Pattern.compile("\\b(out\\s+of\\s+date|outdated|stale|re-?check|re-?verify|double[- ]check|fact[- ]check)\\b", F),
            Pattern.compile("\\bwhat('?s|\\s+has)\\s+changed\\b", F));

    private static final List<Pattern> SINCE_THEN = List.of(
            Pattern.compile("\\bsince\\s+(then|that|we\\s+(talked|spoke|discussed|chatted|last)|you\\s+(told|said|answered)|last\\s+time)\\b", F),
            Pattern.compile("\\bsince\\s*[?.!]*\\s*$", F));

    private static final Pattern FRESHNESS = Pattern.compile(
            "\\b(latest|newest|current(ly)?|today|tonight|right\\s+now|this\\s+(week|month|year|morning)|recent(ly)?|news|headlines?|prices?|pricing|costs?|how\\s+much|fees?|releases?|released|launch(ed|es)?|announce(d|ment)?|versions?|updates?|updated|scores?|won|winner|results?|weather|forecast|stocks?|share\\s+price|exchange\\s+rate|interest\\s+rates?|rankings?|who\\s+is\\s+the\\s+(current|new)|ceo|president|prime\\s+minister|elections?|deadline|schedule|availability|in\\s+stock|still|changed|since|upcoming|20[2-9]\\d)\\b",
            F);

    /** One acknowledgement or greeting: "thanks", "got it", "that was helpful", "hello there". */
    private static final String ACK = "(?:hi+|hello+|hey+|hiya|yo|sup|hola|namaste|good\\s+(?:morning|afternoon|evening|night)"
            + "|thanks?(?:\\s+(?:you|again))?|thank\\s+you(?:\\s+(?:so\\s+much|very\\s+much|again))?|thx|ty|cheers"
            + "|ok(?:ay)?|cool|great|nice(?:\\s+one)?|awesome|perfect|excellent|brilliant|got\\s+it|understood|makes\\s+sense"
            + "|sounds\\s+good|all\\s+good|no\\s+worries|will\\s+do|much\\s+appreciated|appreciate\\s+it|love\\s+it"
            + "|very\\s+(?:helpful|useful)|bye|goodbye|see\\s+you|lol|haha+|hmm+"
            + "|(?:that|this|it)(?:(?:['’]s|\\s+is|\\s+was)(?:\\s+(?:very|really|super|so|quite))?"
            + "\\s+(?:helpful|useful|great|perfect|clear|amazing|awesome|nice|good|interesting|exactly\\s+what\\s+i\\s+needed)"
            + "|\\s+helps|\\s+helped))"
            + "(?:\\s+(?:there|so\\s+much|a\\s+lot|mate|man|bro|buddy|orrery))?";

    /**
     * A whole message made only of acknowledgements ("Thanks, that was helpful!"). Such turns
     * skip memory and tools entirely. Anything with a request in it ("thanks, and Supabase?")
     * does not match and goes the normal way.
     */
    private static final Pattern SMALL_TALK = Pattern.compile(
            "^\\s*" + ACK + "(?:[\\s,.!]+" + ACK + ")*[\\s!.,?\\x{1F642}\\x{1F60A}\\x{1F44D}\\x{1F64F}]*$",
            F);

    private IntentAnalyzer() {}

    public static TurnIntent analyze(String text) {
        String t = text == null ? "" : text.strip();
        if (t.length() <= 70 && SMALL_TALK.matcher(t).matches()) return TurnIntent.SMALL_TALK;
        return new TurnIntent(
                anyFind(RECALL, t),
                anyFind(VERIFY, t),
                anyFind(SINCE_THEN, t),
                FRESHNESS.matcher(t).find(),
                false);
    }

    private static boolean anyFind(List<Pattern> patterns, String text) {
        for (Pattern p : patterns) if (p.matcher(text).find()) return true;
        return false;
    }
}
