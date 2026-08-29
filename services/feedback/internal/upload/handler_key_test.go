package upload

import (
	"strings"
	"testing"
	"time"
)

// The object key is built from two client-supplied strings. Until 2026-08-28 only
// one of them was sanitised, and `record_id` went into the key raw — so a client
// could choose its own prefix. `PutObject` overwrites silently, which makes that an
// arbitrary write into the dataset bucket rather than an untidy key.
//
// These are the first tests in this package that touch the request path at all. The
// `go` job runs gofmt, vet, build, test and govulncheck, and every one of them stays
// green with the auth check, the MIME whitelist and the rate limiter all deleted.
func TestSanitizeKeySegmentBlocksPrefixEscape(t *testing.T) {
	cases := []struct {
		name string
		in   string
		want string
	}{
		// The two that mattered. Neither may keep a slash.
		{"parent traversal", "../../../../etc/passwd", "etc_passwd"},
		{"crossing into the contribution namespace", "contribution/2026-08/OVERWRITE", "contribution_2026-08_OVERWRITE"},
		{"bare slash", "a/b", "a_b"},
		{"leading dots trimmed", "..hidden", "hidden"},

		// A real record id has to survive byte for byte, or this change would
		// break both mobile clients. Every character of a UUID is in the
		// allowlist, which is why sanitising was safe where rejecting was not.
		{"uuid passes through unchanged", "550e8400-e29b-41d4-a716-446655440000", "550e8400-e29b-41d4-a716-446655440000"},

		// Nothing usable left. The caller substitutes a default.
		{"punctuation only", "...", ""},
		{"empty", "", ""},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			if got := sanitizeKeySegment(c.in); got != c.want {
				t.Fatalf("sanitizeKeySegment(%q) = %q, want %q", c.in, got, c.want)
			}
		})
	}
}

func TestSanitizeKeySegmentIsBounded(t *testing.T) {
	long := make([]byte, 5000)
	for i := range long {
		long[i] = 'A'
	}
	got := sanitizeKeySegment(string(long))
	if len(got) != maxKeySegment {
		t.Fatalf("a 5000-byte segment came back %d bytes, want %d", len(got), maxKeySegment)
	}
}

// A slash must not survive under any input, which is the one property the key
// construction depends on. Checked separately from the table because it is the
// invariant rather than a case.
func TestSanitizeKeySegmentNeverEmitsASeparator(t *testing.T) {
	for _, in := range []string{"a/b", "//", "..%2f..", "\\/", "a\\b"} {
		got := sanitizeKeySegment(in)
		for _, r := range got {
			if r == '/' || r == '\\' {
				t.Fatalf("sanitizeKeySegment(%q) = %q, which still contains a separator", in, got)
			}
		}
	}
}

// The key ASSEMBLY, not just the sanitiser.
//
// Testing the sanitiser alone was not enough and a mutation proved it: replacing
// `sanitizeKeySegment(recordID)` with `recordID` at the call site left every test
// green, because none of them went through the assembly. That is the same shape of
// gap this audit found elsewhere — a guard whose test does not cover the place it
// is applied.
func TestBuildObjectKeyCannotBeSteeredOutOfItsPrefix(t *testing.T) {
	at := time.Date(2026, 8, 28, 13, 45, 30, 0, time.UTC)

	cases := []struct {
		name       string
		folder     string
		recordID   string
		original   string
		wantPrefix string
		wantKey    string
	}{
		{
			name:       "a real upload",
			folder:     "feedback",
			recordID:   "550e8400-e29b-41d4-a716-446655440000",
			original:   "page.png",
			wantPrefix: "feedback/2026-08/",
			wantKey:    "feedback/2026-08/550e8400-e29b-41d4-a716-446655440000-page.png",
		},
		{
			name:       "traversal in record_id",
			folder:     "feedback",
			recordID:   "../../../../etc/passwd",
			original:   "x.txt",
			wantPrefix: "feedback/2026-08/",
			wantKey:    "feedback/2026-08/etc_passwd-x.txt",
		},
		{
			name:       "crossing into the contribution namespace",
			folder:     "feedback",
			recordID:   "contribution/2026-08/OVERWRITE",
			original:   "x.txt",
			wantPrefix: "feedback/2026-08/",
			wantKey:    "feedback/2026-08/contribution_2026-08_OVERWRITE-x.txt",
		},
		{
			name:       "traversal in the filename too",
			folder:     "contribution",
			recordID:   "550e8400-e29b-41d4-a716-446655440000",
			original:   "../../evil.png",
			wantPrefix: "contribution/2026-08/",
			wantKey:    "contribution/2026-08/550e8400-e29b-41d4-a716-446655440000-evil.png",
		},
		{
			name:       "no record_id falls back to a timestamp",
			folder:     "feedback",
			recordID:   "",
			original:   "x.txt",
			wantPrefix: "feedback/2026-08/",
			wantKey:    "feedback/2026-08/134530-x.txt",
		},
		{
			name:       "a record_id of pure punctuation is treated as absent",
			folder:     "feedback",
			recordID:   "../..",
			original:   "x.txt",
			wantPrefix: "feedback/2026-08/",
			wantKey:    "feedback/2026-08/134530-x.txt",
		},
	}

	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			got := buildObjectKey(c.folder, at, c.recordID, c.original)
			if got != c.wantKey {
				t.Fatalf("buildObjectKey = %q, want %q", got, c.wantKey)
			}
			if !strings.HasPrefix(got, c.wantPrefix) {
				t.Fatalf("key %q escaped the prefix %q", got, c.wantPrefix)
			}
			// The invariant: exactly two separators, the ones the prefix puts
			// there. Any third means a client chose part of the path.
			if n := strings.Count(got, "/"); n != 2 {
				t.Fatalf("key %q has %d separators, want 2", got, n)
			}
		})
	}
}
