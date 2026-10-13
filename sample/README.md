# Sample: PollinationsDemo (Kodular steps)

No `.aia` is provided for Kodular. Kodular's project export format was not checked against App Inventor's,
so build the screen by hand. The App Inventor sample in `pollinations-app-inventor/sample/` uses the same
blocks, and its `README.md` has the same steps.

## Build the screen

1. Import `release/ai.pollinations.ai2.aix` as an extension (see the README).
2. Add a **TextBox** named `PromptBox` (hint "Ask something"), a **Button** named `AskButton` (text "Ask"),
   a **Label** named `AnswerLabel`, and the non-visible **Pollinations** component `Pollinations1`.
3. Select `Pollinations1` and set **ApiKey** (or leave it empty and use `StartSignIn`).

## Blocks

1. **when AskButton.Click**: call `Pollinations1.AskText` with `PromptBox.Text`.
2. **when Pollinations1.GotText** (`response`): set `AnswerLabel.Text` to `response`.
3. **when Pollinations1.ErrorOccurred** (`errorCode`, `message`): set `AnswerLabel.Text` to `message`.

## Check

With a key set, type a question and tap **Ask**. The answer appears in the label. With no key, the label shows
the message from `ErrorOccurred`.

**Not verified in Kodular Creator.** The steps follow the App Inventor sample. Kodular's menu names and
the block layout may differ.
