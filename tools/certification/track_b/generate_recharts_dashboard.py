#!/usr/bin/env python3
"""
generate_recharts_dashboard.py
Embeds live JSON metrics from hardware_validation_runner.py into the Recharts Latency Dashboard.
"""
import os
import json
import sys

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
REPORT_FILE = os.path.join(SCRIPT_DIR, "track_b_silicon_report.json")
TEMPLATE_HTML = os.path.join(SCRIPT_DIR, "recharts_latency_dashboard.html")
OUTPUT_HTML = os.path.join(SCRIPT_DIR, "recharts_latency_dashboard.html")

def generate_dashboard(report_path=REPORT_FILE, output_path=OUTPUT_HTML):
    if not os.path.exists(report_path):
        print(f"[Dashboard] Report file {report_path} not found. Using default dataset.")
        return

    with open(report_path, "r", encoding="utf-8") as f:
        report_data = json.load(f)

    if not os.path.exists(TEMPLATE_HTML):
        print(f"[Dashboard] Template {TEMPLATE_HTML} not found.")
        return

    with open(TEMPLATE_HTML, "r", encoding="utf-8") as f:
        html_content = f.read()

    # Inject the updated report data into DEFAULT_REPORT variable
    replacement = f"const DEFAULT_REPORT = {json.dumps(report_data, indent=12)};"
    
    # Replace default report block
    import re
    updated_html = re.sub(
        r"const DEFAULT_REPORT = \{[\s\S]*?\};",
        replacement,
        html_content,
        count=1
    )

    with open(output_path, "w", encoding="utf-8") as f:
        f.write(updated_html)

    print(f"[Dashboard] Successfully updated Recharts dashboard at: {output_path}")

if __name__ == "__main__":
    report_arg = sys.argv[1] if len(sys.argv) > 1 else REPORT_FILE
    generate_dashboard(report_arg)
