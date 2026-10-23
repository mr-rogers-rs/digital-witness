package main

import (
	"encoding/json"
	"flag"
	"fmt"
	"os"
)

// VerifyReport is the JSON contract dwverify emits on stdout.
//
// `valid` is true ONLY when every applicable check has passed. The previous
// implementation unconditionally set Valid:true with ChunksChecked:0, which
// gave a false assurance of integrity to anyone piping the tool into a
// pipeline. This is fail-closed: unknown / unimplemented checks surface as
// invalid with a reason, never as silently-valid.
type VerifyReport struct {
	Valid         bool     `json:"valid"`
	Reason        string   `json:"reason,omitempty"`
	ChunksChecked int      `json:"chunksChecked"`
	Errors        []string `json:"errors,omitempty"`
}

func main() {
	input := flag.String("input", "", "Path to proofs JSON (sidecar)")
	flag.Parse()
	if *input == "" {
		fmt.Fprintln(os.Stderr, "usage: dwverify -input proofs.json")
		os.Exit(2)
	}

	report := verify(*input)

	enc := json.NewEncoder(os.Stdout)
	enc.SetIndent("", "  ")
	_ = enc.Encode(report)

	// Exit non-zero on invalid so CI / shell pipelines can't treat a failed
	// verification as success (audit C8).
	if !report.Valid {
		os.Exit(1)
	}
}

// verify loads the proofs sidecar and runs the implemented subset of checks.
// Anything not yet implemented is recorded as an error so the report fails
// closed rather than silently passing.
func verify(path string) VerifyReport {
	data, err := os.ReadFile(path)
	if err != nil {
		return fail(fmt.Sprintf("cannot read %s: %v", path, err))
	}

	// Parse the proofs JSON loosely. We don't yet validate against the schema
	// (docs/schemas/proofs.schema.json) or verify the hash chain, drand beacon,
	// OpenTimestamps proof, or hardware attestation — but we MUST NOT claim
	// validity for content we haven't checked.
	var doc map[string]any
	if err := json.Unmarshal(data, &doc); err != nil {
		return fail(fmt.Sprintf("invalid JSON in %s: %v", path, err))
	}

	var errs []string

	// A proofs sidecar with no chunk entries is not "valid: true with zero
	// chunks" — it's an empty / malformed proof and must fail closed.
	chunks, _ := doc["chunks"].([]any)
	if len(chunks) == 0 {
		errs = append(errs, "no chunk entries found in proofs file")
	}

	// TODO(load schema): validate against docs/schemas/proofs.schema.json.
	errs = append(errs, "schema validation not yet implemented")
	// TODO(hash chain): recompute SHA-256 chain from H_0..H_n and compare.
	errs = append(errs, "hash-chain verification not yet implemented")
	// TODO(drand): re-fetch the beacon from the drand network and confirm the
	// round/signature in the proof matches (currently only read from proof).
	errs = append(errs, "drand beacon verification not yet implemented")
	// TODO(ots): locate the .ots sidecar and verify against a Bitcoin header.
	errs = append(errs, "OpenTimestamps verification not yet implemented")
	// TODO(attestation): verify the hardware key attestation certificate chain.
	errs = append(errs, "hardware attestation verification not yet implemented")

	return VerifyReport{
		Valid:         false,
		Reason:        "verifier is a stub; no checks are implemented yet",
		ChunksChecked: len(chunks),
		Errors:        errs,
	}
}

func fail(reason string) VerifyReport {
	return VerifyReport{Valid: false, Reason: reason}
}
