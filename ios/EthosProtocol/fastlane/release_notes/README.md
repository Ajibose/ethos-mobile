# Release notes overrides

"What's New" is generated from the conventional-commit PR titles merged since the previous
`v*` tag (`.github/scripts/generate_release_notes.py`). To replace it for one version,
commit hand-written text here before tagging:

```
release_notes/
  1.1.0/
    en-US.txt      # default locale; also used by locales without their own file
    de-DE.txt      # optional per-locale translation
```

Precedence, highest first:

1. `release_notes/<version>/<locale>.txt`
2. The `release_notes` input of a manual workflow run (applies to every locale)
3. Generated notes

Override text must be 4,000 characters or fewer. Longer text fails the run rather than
being cut.
