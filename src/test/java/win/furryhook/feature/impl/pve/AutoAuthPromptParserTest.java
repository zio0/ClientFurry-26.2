package win.furryhook.feature.impl.pve;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutoAuthPromptParserTest {
    @Test
    void recognizesRegistrationPrompts() {
        List<String> prompts = List.of(
                "Please register using /register <password> <password>",
                "\u00a7cYou need to register before playing",
                "Создайте пароль для регистрации",
                "Зарегистрируйтесь: /reg пароль пароль"
        );

        for (String prompt : prompts) {
            assertEquals(
                    AutoAuthPromptParser.Prompt.REGISTER,
                    AutoAuthPromptParser.parse(prompt).orElseThrow(),
                    prompt
            );
        }
    }

    @Test
    void recognizesLoginPrompts() {
        List<String> prompts = List.of(
                "Please log in using /login <password>",
                "You must login before playing",
                "Введите свой пароль",
                "Авторизуйтесь: /l пароль",
                "Use /log\u200Bin secret"
        );

        for (String prompt : prompts) {
            assertEquals(
                    AutoAuthPromptParser.Prompt.LOGIN,
                    AutoAuthPromptParser.parse(prompt).orElseThrow(),
                    prompt
            );
        }
    }

    @Test
    void registrationWinsWhenPromptAlsoMentionsLogin() {
        assertEquals(
                AutoAuthPromptParser.Prompt.REGISTER,
                AutoAuthPromptParser.parse(
                        "New players use /register pass pass; existing players use /login pass"
                ).orElseThrow()
        );
    }

    @Test
    void ignoresSuccessAndUnrelatedMessages() {
        List<String> messages = List.of(
                "Successfully logged in",
                "Registration completed successfully",
                "Регистрация прошла успешно",
                "Your password was changed",
                "Welcome to the server",
                "Do not register this account again",
                "modern helpers are available on the website"
        );

        for (String message : messages) {
            assertTrue(AutoAuthPromptParser.parse(message).isEmpty(), message);
        }
    }
}
