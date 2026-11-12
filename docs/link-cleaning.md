# Link cleaning

SeBrain removes known tracking parameters when captured text enters the encrypted inbox.
Cleaning happens before deduplication, so the canonical content and its keyed deduplication tag
are based on the cleaned text.

The cleaner only processes `http` and `https` URLs embedded in text. It removes `utm_*`,
`gclid`, `dclid`, `fbclid`, `msclkid`, Mailchimp parameters, and HubSpot tracking parameters.
Unknown parameters, functional query values, fragments, Markdown syntax and surrounding
punctuation are preserved.

Coverage is in `ingest/src/test/kotlin/com/sebrain/ingest/IngestTest.kt`. This change is
unit-tested. A Pixel Share Intent check was attempted after installation, but the device was
locked and dozing (`deviceLocked=1`, `mWakefulness=Dozing`), so the Android end-to-end result is
not claimed as verified.
