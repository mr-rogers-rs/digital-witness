#!/usr/bin/env python3
"""
Digital Witness -- Translation Review Aid

Produces a side-by-side Markdown table of the English UI strings against a
translation, so a native-speaker reviewer can check meaning against the source
without reading two XML files in parallel. The safety-critical strings (the
duress code, panic wipe, anonymity-vs-evidence choice, network-exposure
options, jurisdiction warnings, capture-failure alerts) are listed first and
flagged: those are the ones a user acts on under pressure, where a fluent
mistranslation is worse than leaving the English in place.

It also prints a short parity report to stderr: keys present in English but not
the translation (they fall back to English -- safe, just untranslated), keys in
the translation with no English counterpart (dead -- a bug), and any
format-argument mismatch such as a `%1$s` that was dropped or reordered (a
crash or wrong-value bug).

Usage:
    python tools/translation_review.py                 # sr-Latn -> translation_review.md
    python tools/translation_review.py --locale b+sr+Latn -o review.md
    python tools/translation_review.py -o -            # write the table to stdout

Paths default to this repo's layout and are resolved relative to the repo root,
so it runs from any working directory. Override with --english / --translated
if your locale directory differs.

Exit codes:
    0 -- table written; translation may be partial (missing keys only warn)
    1 -- a real defect: dead keys, or a format-argument mismatch
    2 -- input error (a strings.xml file could not be read)
"""

import argparse
import os
import re
import sys

# Repo root = parent of the tools/ directory this script lives in.
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "android-app", "app", "src", "main", "res")

STRING = re.compile(r'<string name="([a-z_0-9]+)">(.*?)</string>', re.S)
FMT = re.compile(r"%(\d+)\$[-0-9.]*[sdf]|%[-0-9.]*[sdf]")

# Curated list of the strings a user acts on under pressure. This is a
# prioritization aid, not an exhaustive security boundary: over- or
# under-marking only changes review order, since a reviewer reads everything.
# Keep it roughly in sync when adding safety-relevant strings. It is a manual
# list because the section-level SAFETY comments in values/strings.xml, if
# followed literally, bleed onto every string that trails them.
SAFETY = set("""
lock_wait_seconds lock_threshold_wiping lock_incorrect_locked lock_incorrect_count lock_incorrect
panic_title panic_message panic_confirm panic_done_toast uninstall_title uninstall_message
fail_camera_title fail_stalled_title fail_stalled_body fail_mic_title fail_mic_body
fail_upload_title fail_upload_body fail_encrypt_title fail_encrypt_body
fail_recording_title fail_recording_body fail_muxer_title fail_muxer_body
nc_fingerprint_message set_fingerprint_msg
ob_pin_tip ob_done_note
ob_mode_anon_title ob_mode_anon_desc ob_mode_ev_title ob_mode_ev_desc
ob_net_tor_title ob_net_tor_desc ob_net_vpn_title ob_net_vpn_desc ob_net_none_title ob_net_none_desc
ob_net_status_none ob_net_status_tor_ok ob_net_status_tor_blocked ob_net_status_vpn_ok ob_net_status_blocked
set_juris_dropbox set_juris_gdrive set_juris_webdav set_juris_nextcloud
set_duress_label set_duress_hint set_duress_explain set_err_duress_same
set_disguise_warning set_gps_warning set_mode_explain set_secure_net_explain set_tor_explain
""".split())


def read_strings(path):
    """Return (ordered [(key, raw_value)], {key: raw_value})."""
    try:
        text = open(path, encoding="utf-8").read()
    except OSError as e:
        sys.exit("error: cannot read %s: %s" % (path, e))
    pairs = STRING.findall(text)
    return pairs, dict(pairs)


def show(value):
    """Render a raw resource value for a table cell."""
    v = (value.replace("\\n", " / ").replace("\\'", "'")
              .replace("&amp;", "&").replace("&gt;", ">").replace("&lt;", "<"))
    return " ".join(v.split()).replace("|", "\\|").strip()


def fmt_args(value):
    return sorted(FMT.findall(value))


def main():
    ap = argparse.ArgumentParser(description="Side-by-side translation review aid.")
    ap.add_argument("--locale", default="b+sr+Latn",
                    help="Android locale qualifier of the translation dir "
                         "(default: b+sr+Latn). Ignored if --translated is given.")
    ap.add_argument("--english", help="Path to the source strings.xml "
                    "(default: values/strings.xml under the repo).")
    ap.add_argument("--translated", help="Path to the translated strings.xml "
                    "(default: values-<locale>/strings.xml under the repo).")
    ap.add_argument("-o", "--output", default="translation_review.md",
                    help="Output Markdown path, or - for stdout "
                         "(default: translation_review.md).")
    args = ap.parse_args()

    en_path = args.english or os.path.join(RES, "values", "strings.xml")
    tr_path = args.translated or os.path.join(RES, "values-%s" % args.locale, "strings.xml")

    en_pairs, en = read_strings(en_path)
    _, tr = read_strings(tr_path)

    # ── Parity / format report (stderr) ──────────────────────────────────
    missing = [k for k, _ in en_pairs if k not in tr]
    extra = [k for k in tr if k not in en]
    fmt_bad = [k for k, v in en_pairs if k in tr and fmt_args(v) != fmt_args(tr[k])]

    print("english: %d keys   translated: %d keys" % (len(en), len(tr)), file=sys.stderr)
    print("untranslated (fall back to English): %d" % len(missing), file=sys.stderr)
    for k in missing:
        print("   - %s" % k, file=sys.stderr)
    if extra:
        print("DEAD keys (in translation, not in English): %d" % len(extra), file=sys.stderr)
        for k in extra:
            print("   + %s" % k, file=sys.stderr)
    if fmt_bad:
        print("FORMAT-ARG MISMATCH: %d" % len(fmt_bad), file=sys.stderr)
        for k in fmt_bad:
            print("   ! %s  en=%s  tr=%s" % (k, fmt_args(en[k]), fmt_args(tr[k])), file=sys.stderr)

    # ── Side-by-side table ───────────────────────────────────────────────
    safety = [(k, v) for k, v in en_pairs if k in SAFETY]
    rest = [(k, v) for k, v in en_pairs if k not in SAFETY]
    cmd = "python tools/translation_review.py --locale %s" % args.locale

    def rows(section):
        out = ["| key | English | Translation |", "|---|---|---|"]
        for k, v in section:
            out.append("| `%s` | %s | %s |" % (k, show(v), show(tr.get(k, "‹MISSING — falls back to English›"))))
        return "\n".join(out)

    doc = "\n".join([
        "# Translation review — %s" % args.locale,
        "",
        "Generated by `%s`." % cmd,
        "Read the translation against the English; check **meaning first**, then",
        "fluency, then length. `\\n` in the source is shown here as ` / `.",
        "",
        "To fix a string, edit its value in `values-%s/strings.xml`. Deleting an" % args.locale,
        "entry falls back to English, so partial revision is always safe. Then",
        "regenerate this file with the command above.",
        "",
        "## SAFETY strings — review these first, for meaning",
        "",
        "The user acts on these under pressure. A fluent mistranslation here is",
        "worse than English, because it reads as authoritative.",
        "",
        rows(safety),
        "",
        "## Everything else",
        "",
        rows(rest),
        "",
    ])

    if args.output == "-":
        sys.stdout.write(doc)
    else:
        with open(args.output, "w", encoding="utf-8", newline="\n") as f:
            f.write(doc)
        print("wrote %s  (%d SAFETY, %d other, %d total)"
              % (args.output, len(safety), len(rest), len(en_pairs)), file=sys.stderr)

    return 1 if (extra or fmt_bad) else 0


if __name__ == "__main__":
    sys.exit(main())
