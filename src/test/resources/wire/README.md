# Synthetic wire fixtures

This directory holds project-authored golden byte streams for the codec tests.

- A fixture is a UTF-8 text file written and read by
  `org.tortodrone.support.WireFixture`. It has the header
  `tortodrone-wire-fixture 1`, a stable lowercase `name`, `source synthetic` and one
  base64 `frame` line per complete frame (length prefix included).
- Frames are produced by the server codec or an independent synthetic encoder. They
  are never client captures, packet dumps, extracted game data or decompiled
  material, and a fixture declaring any other source is refused.
- A fixture becomes authoritative only when the native layout it covers has
  evidence from the installed client reader or observed stock-client behaviour.
  Adding bytes now is not a claim that the layout is correct.
- Phase 2 and Phase 7 own the first real mission and cross-feature streams. The
  container and its focused test (`SyntheticWireFixtureTest`) exist in Phase 0 so
  those streams have one checked format and one comparison path.
