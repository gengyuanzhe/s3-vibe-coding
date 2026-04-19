#!/usr/bin/env python3
"""
S3 V4 签名请求脚本（基于 boto3）
用法: python3 s3-curl.py <command> [args]

示例:
  python3 s3-curl.py ls                        # 列出所有桶
  python3 s3-curl.py mb my-bucket              # 创建桶
  python3 s3-curl.py rb my-bucket              # 删除桶
  python3 s3-curl.py put my-bucket key file    # 上传文件
  python3 s3-curl.py get my-bucket key         # 下载文件到 stdout
  python3 s3-curl.py ls my-bucket              # 列出桶内文件
  python3 s3-curl.py rm my-bucket key          # 删除文件
"""

import sys
import os
import boto3
from botocore.config import Config

ENDPOINT = os.environ.get("S3_ENDPOINT", "http://localhost:5080")
ACCESS_KEY = "AKIAIOSFODNN7EXAMPLE"
SECRET_KEY = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"

s3 = boto3.client(
    "s3",
    endpoint_url=ENDPOINT,
    aws_access_key_id=ACCESS_KEY,
    aws_secret_access_key=SECRET_KEY,
    region_name="us-east-1",
    config=Config(
        signature_version="s3v4",
        s3={"addressing_style": "path", "payload_signing_enabled": True},
    ),
)


def cmd_ls(args):
    if not args:
        resp = s3.list_buckets()
        if resp["Buckets"]:
            for b in resp["Buckets"]:
                print(f"  {b['Name']}")
        else:
            print("  (no buckets)")
    else:
        bucket = args[0]
        prefix = args[1] if len(args) > 1 else ""
        resp = s3.list_objects_v2(Bucket=bucket, Prefix=prefix)
        if resp.get("Contents"):
            for obj in resp["Contents"]:
                print(f"  {obj['Key']}  ({obj['Size']} bytes)")
        else:
            print("  (empty)")


def cmd_mb(args):
    bucket = args[0]
    s3.create_bucket(Bucket=bucket)
    print(f"Created bucket: {bucket}")


def cmd_rb(args):
    bucket = args[0]
    s3.delete_bucket(Bucket=bucket)
    print(f"Deleted bucket: {bucket}")


def cmd_put(args):
    bucket, key, filepath = args[0], args[1], args[2]
    s3.upload_file(filepath, bucket, key)
    print(f"Uploaded: {bucket}/{key}")


def cmd_get(args):
    bucket, key = args[0], args[1]
    resp = s3.get_object(Bucket=bucket, Key=key)
    sys.stdout.buffer.write(resp["Body"].read())


def cmd_rm(args):
    bucket, key = args[0], args[1]
    s3.delete_object(Bucket=bucket, Key=key)
    print(f"Deleted: {bucket}/{key}")


commands = {
    "ls": cmd_ls,
    "mb": cmd_mb,
    "rb": cmd_rb,
    "put": cmd_put,
    "get": cmd_get,
    "rm": cmd_rm,
}

if __name__ == "__main__":
    if len(sys.argv) < 2 or sys.argv[1] not in commands:
        print(__doc__)
        sys.exit(1)
    commands[sys.argv[1]](sys.argv[2:])
