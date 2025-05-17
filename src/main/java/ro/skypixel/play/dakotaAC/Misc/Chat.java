package ro.skypixel.play.dakotaAC.Misc;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import ro.skypixel.play.dakotaAC.Alert;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Chat implements Listener {

    // List of banned words (should be in their "clean", lowercase form)
    private static final List<String> BANNED_WORDS = Arrays.asList(
            "fuck", "fucked", "fucker", "fuckers", "fucking", "motherfucker", "mf",
            "shit", "shitty", "bullshit", "ass", "asses", "asshole", "bitch", "bitches",
            "bastard", "damn", "damned", "goddamn", "goddamned", "crap", "dick", "dicks",
            "dickhead", "jerk", "prick", "cock", "piss", "pissed", "pussy", "whore",
            "slut", "cunt", "arse", "arsehole", "bloody", "bugger", "bollocks", "screw",
            "screwed", "freaking", "freakin", "damnit", "wanker", "twat", "twits",
            "nutcase", "idiot", "moron", "imbecile", "dipshit", "jackass", "horsecrap",
            "horseshit", "sonofabitch", "retard"
            // Add more words as needed
    );

    // Pre-compiled patterns for banned words for better performance
    private static final List<Pattern> BANNED_PATTERNS = new ArrayList<>();

    static {
        for (String word : BANNED_WORDS) {
            BANNED_PATTERNS.add(buildFlexiblePatternForSwear(word));
        }
    }

    /**
     * Builds a flexible, case-insensitive regex pattern for a given swear word.
     * This pattern allows for common separators between letters and handles basic leet speak.
     * @param swear The base swear word (e.g., "fuck").
     * @return A compiled Pattern object.
     */
    private static Pattern buildFlexiblePatternForSwear(String swear) {
        StringBuilder leetRegex = new StringBuilder("(?i)\\b(?:"); // (?i) for case-insensitive, \\b for word boundary, (?: non-capturing group
        for (int i = 0; i < swear.length(); i++) {
            char c = swear.charAt(i);
            leetRegex.append(getLeetRegexForChar(c)); // Append leet regex for the character
            if (i < swear.length() - 1) {
                // Allow optional separators (whitespace or punctuation) between letters
                // This matches zero or more occurrences of whitespace or punctuation.
                leetRegex.append("[\\s\\p{Punct}]*");
            }
        }
        leetRegex.append(")\\b"); // End non-capturing group and word boundary
        return Pattern.compile(leetRegex.toString());
    }

    /**
     * Provides a regex snippet for a character, including common leet speak alternatives.
     * @param c The character to get leet alternatives for.
     * @return A regex string part (e.g., "[e3€]" for 'e').
     */
    private static String getLeetRegexForChar(char c) {
        char lowerC = Character.toLowerCase(c);
        switch (lowerC) {
            case 'a': return "[a@4]";
            case 'e': return "[e3€]";
            case 'i': return "[i!1l]"; // 'l' can sometimes look like 'i' or '1'
            case 'o': return "[o0]";
            case 's': return "[s$5]";
            case 't': return "[t7+]"; // Added '7' for 't'
            // Add more common leet substitutions as needed
            default:
                // For any other character, quote it to ensure it's treated literally in the regex
                return Pattern.quote(String.valueOf(c));
        }
    }

    @EventHandler
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        if (event.isCancelled()) {
            return;
        }

        String originalMessage = event.getMessage();
        FilterResult filterResult = filterSwearWords(originalMessage);

        // Set the potentially modified message
        event.setMessage(filterResult.filteredMessage);

        // Alert if swears were found and replaced
        if (filterResult.foundSwears) {
            Alert.getInstance().alert("Chat", event.getPlayer());
        }
    }

    /**
     * Filters a message for swear words, replacing them with asterisks.
     * @param message The original message string.
     * @return A FilterResult object containing the filtered message and a boolean indicating if swears were found.
     */
    private FilterResult filterSwearWords(String message) {
        String currentMessage = message;
        boolean foundSwearsOverall = false;

        for (Pattern pattern : BANNED_PATTERNS) {
            Matcher matcher = pattern.matcher(currentMessage);
            StringBuffer sbForThisPattern = new StringBuffer();
            boolean madeReplacementWithThisPattern = false;

            while (matcher.find()) {
                madeReplacementWithThisPattern = true;
                // The overall flag is set if any pattern makes a replacement
                foundSwearsOverall = true;
                // Replace the matched part (e.g., "f u c k" or "f@ck") with asterisks of the same length
                matcher.appendReplacement(sbForThisPattern, repeat('*', matcher.group(0).length()));
            }

            if (madeReplacementWithThisPattern) {
                matcher.appendTail(sbForThisPattern);
                currentMessage = sbForThisPattern.toString();
            }
        }
        return new FilterResult(currentMessage, foundSwearsOverall);
    }

    /**
     * Repeats a character a specified number of times.
     * @param ch The character to repeat.
     * @param count The number of times to repeat the character.
     * @return A string consisting of the repeated character.
     */
    private static String repeat(char ch, int count) {
        if (count < 0) {
            throw new IllegalArgumentException("Count cannot be negative.");
        }
        StringBuilder sb = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            sb.append(ch);
        }
        return sb.toString();
    }

    /**
     * Helper class to store the result of the filtering process.
     */
    private static class FilterResult {
        final String filteredMessage;
        final boolean foundSwears;

        FilterResult(String filteredMessage, boolean foundSwears) {
            this.filteredMessage = filteredMessage;
            this.foundSwears = foundSwears;
        }
    }
}
