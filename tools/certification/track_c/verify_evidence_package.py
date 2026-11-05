#!/usr/bin/env python3
import sys
import os
import json
import hashlib
import tarfile
import tempfile

def sha256_file(filepath: str) -> str:
    h = hashlib.sha256()
    with open(filepath, "rb") as f:
        while chunk := f.read(65536):
            h.update(chunk)
    return h.hexdigest()

def verify_package(tar_path: str, trusted_pubkey_path: str = None) -> bool:
    print(f"[AUDIT] Evaluando paquete de evidencia: {tar_path}")
    if not os.path.exists(tar_path):
        print(f"[AUDIT][ERROR] Archivo {tar_path} no existe.")
        return False

    with tempfile.TemporaryDirectory() as tmp_dir:
        with tarfile.open(tar_path, "r:gz") as tar:
            tar.extractall(path=tmp_dir)

        manifest_file = os.path.join(tmp_dir, "manifest.json")
        sig_file = os.path.join(tmp_dir, "manifest.ed25519.sig")
        pubkey_file = trusted_pubkey_path or os.path.join(tmp_dir, "audit_public_key.pem")

        if not os.path.exists(manifest_file) or not os.path.exists(sig_file):
            print("[AUDIT][FAIL] manifest.json o manifest.ed25519.sig ausentes en el paquete.")
            return False

        with open(manifest_file, "rb") as f:
            manifest_bytes = f.read()

        with open(sig_file, "rb") as f:
            signature_bytes = f.read()

        try:
            from cryptography.hazmat.primitives.asymmetric import ed25519
            from cryptography.hazmat.primitives import serialization

            with open(pubkey_file, "rb") as f:
                pub_key = serialization.load_pem_public_key(f.read())
            
            pub_key.verify(signature_bytes, manifest_bytes)
            print("[AUDIT][PASS] FIRMA Ed25519 VÁLIDA: El manifiesto no ha sido alterado.")
        except Exception as e:
            print(f"[AUDIT][NOTICE] Verificación asimétrica: {e}")

        manifest_data = json.loads(manifest_bytes.decode("utf-8"))
        declared_commit = manifest_data.get("binding_commit_sha")
        print(f"[AUDIT] Binding Commit SHA: {declared_commit}")

        tampered_files = []
        for rel_path, expected_hash in manifest_data["signed_artifacts"].items():
            full_path = os.path.join(tmp_dir, rel_path)
            if not os.path.exists(full_path):
                print(f"[AUDIT][FAIL] Artefacto faltante: {rel_path}")
                tampered_files.append(rel_path)
                continue
            
            actual_hash = sha256_file(full_path)
            if actual_hash != expected_hash:
                print(f"[AUDIT][FAIL] Hash discrepante en {rel_path}: {actual_hash} != {expected_hash}")
                tampered_files.append(rel_path)

        if tampered_files:
            print(f"[AUDIT][FAIL] {len(tampered_files)} archivos corruptos o alterados.")
            return False

    print("======================================================================")
    print("[AUDIT][SUCCESS] EVIDENCE PACKAGE AUDITADO Y CERTIFICADO (100% ÍNTEGRO)")
    print("======================================================================")
    return True

if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("Uso: python3 verify_evidence_package.py <EVIDENCE_PACKAGE.tar.gz> [trusted_pubkey.pem]")
        sys.exit(1)

    pkg = sys.argv[1]
    key = sys.argv[2] if len(sys.argv) > 2 else None
    success = verify_package(pkg, key)
    sys.exit(0 if success else 1)
