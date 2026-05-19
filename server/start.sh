#!/bin/bash
cd "$(dirname "$0")"
python3 -m venv .venv 2>/dev/null
source .venv/bin/activate
pip install -r requirements.txt -q
python3 server.py
