# Reflection Warnings Fix

## Fixed Files

### claij/stt/record.clj
**Problem:** Lines 188, 190, 191 had reflection warnings
- `.substring` calls without type hints
- `.length` call without type hint

**Solution:** Added type hints to `lazy-split` function:
```clojure
(defn lazy-split [^String s re]
  (let [^java.util.regex.Matcher m (re-matcher re s)]
    ...))
```

**Result:** ✅ Zero reflection warnings in record.clj

## Coding Guidelines Updated

Added new section: **"Performance: Avoid Reflection"**

Key points:
- Always enable `*warn-on-reflection*`
- Reflection is 100x slower than direct calls
- Use type hints on function parameters and let bindings
- Common hints: `^String`, `^Long`, `^java.util.regex.Matcher`, etc.
- Strategy: compile → watch warnings → add hints → verify zero warnings

## Remaining Warnings (Not Fixed)

Other files still have reflection warnings:
- `claij/tts/playback.clj` - 6 warnings
  - Lines 41, 48, 57, 66, 122, 123
  - FileOutputStream and File methods

These can be fixed later using the same approach.

## Verification

```bash
cd /home/jules/src/claij
clojure -M -e "(set! *warn-on-reflection* true) (require 'claij.stt.record)"
# No warnings for record.clj ✅
```
