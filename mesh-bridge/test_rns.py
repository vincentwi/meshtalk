#!/usr/bin/env python3
"""Quick test: can we init Reticulum from the venv?"""
import sys, os, time
print("Python:", sys.version, flush=True)
print("Starting RNS import...", flush=True)
import RNS
print(f"RNS version: {RNS.__version__}", flush=True)
print("Creating Reticulum instance...", flush=True)
t0 = time.time()
try:
    r = RNS.Reticulum(configdir=None, loglevel=RNS.LOG_VERBOSE)
    print(f"Reticulum created in {time.time()-t0:.1f}s", flush=True)
    ident = RNS.Identity()
    print(f"Identity: {RNS.prettyhexrep(ident.hash)}", flush=True)
    dest = RNS.Destination(ident, RNS.Destination.IN, RNS.Destination.SINGLE, "test", "ping")
    print(f"Destination: {RNS.prettyhexrep(dest.hash)}", flush=True)
    print("SUCCESS - Reticulum works!", flush=True)
except Exception as e:
    print(f"ERROR after {time.time()-t0:.1f}s: {e}", flush=True)
    import traceback; traceback.print_exc()
