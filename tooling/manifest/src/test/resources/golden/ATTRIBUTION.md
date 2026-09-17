# Golden manifests: where they come from

These files are real ColdSpot manifests, captured from a coverage build of this repository's own
sample app and tooling sources, and the shared manifest embeds the full text of that build's
changed source files.

They were written by kotlinx-serialization before ColdSpot's own codec replaced it, as schema 6,
and have been brought up to the current schema by hand since, exactly as the codec lays the
fields out; they guard that the codec writes them back byte for byte. What was added by hand,
and is therefore not what a build captured:

- schema 7: `resetToken`, `null`.
- schema 8: `coldspotVersion`, `"0.1.0"`, and `head.branch`: `"main"`, the branch the build ran
  on. `schemaVersion` itself in every file, the module manifests included, whose shape did not
  change.
