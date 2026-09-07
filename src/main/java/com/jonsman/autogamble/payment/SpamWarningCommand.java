package com.jonsman.autogamble.payment;

import java.util.function.Consumer;
import org.slf4j.LoggerFactory;

public final class SpamWarningCommand {
   private SpamWarningCommand() {
   }

   public static boolean validText(String text) {
      return text != null
         && !text.isBlank()
         && text.length() <= 200
         && text.codePoints().noneMatch(c -> Character.isISOControl(c) || Character.getType(c) == 16 || c == 8232 || c == 8233);
   }

   public static void execute(boolean dry, String username, String message, Consumer<String> send) {
      if (username != null && username.matches("[A-Za-z0-9_]{3,16}") && validText(message)) {
         if (dry) {
            LoggerFactory.getLogger("autogamble").info("[AutoGamble] DRY RUN: Would warn {} for payment spam", username);
         } else {
            send.accept("msg " + username + " " + message);
            LoggerFactory.getLogger("autogamble").info("[AutoGamble] Warning sent to {}", username);
         }
      } else {
         throw new IllegalArgumentException("Invalid warning content");
      }
   }
}
