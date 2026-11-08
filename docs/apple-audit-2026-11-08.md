# Apple build and roadmap refinement — 2026-11-08

## iOS document metadata parity

Document result subtitles now show the original filename and resolved tag names. Unknown tags fall back to their numeric IDs. Existing added-date ordering is retained; Android ordering alignment and parity remain open.

## Verification

- `python3 scripts/check_swift.py` passed: production Foundation typecheck, Swift source parse and client fixture checks.
- Full unsigned iOS simulator build passed with Xcode 27.2 and iOS 27.2 SDK, using the repository's scheme and `CODE_SIGNING_ALLOWED=NO`.
- The build includes the UI changes above.
- No live service, physical device, signed archive or store upload was tested. Simulator installation/interactive journeys remain separate from compilation.

Local command logs are in the fleet audit directory `native-apple/`; they are not runtime data or release artifacts.
