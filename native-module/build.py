#!/usr/bin/env python3
"""从设备原库生成补丁库并打包成 KernelSU/Magisk 模块。

用法：python3 build.py <原始 libSpecailizerPLService.so> [输出 zip]
"""
import hashlib, os, shutil, subprocess, sys, zipfile

OFFSET = 0x1B0B4                 # isVocalAdjustSupported 入口
ORIG_BYTES = bytes.fromhex('3f2303d5ff4305d1')   # paciasp; sub sp, sp, #0x150
NEW_BYTES = bytes.fromhex('20008052c0035fd6')    # mov w0, #1; ret
EXPECT_SHA = 'b38a6d3507f5bbbb504b609540df3b458cb28faa24bc4ac67e72ef327113f5ea'
DEST_REL = 'system/system_ext/lib64/libSpecailizerPLService.so'

def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    src = sys.argv[1]
    out_zip = sys.argv[2] if len(sys.argv) > 2 else 'mss_native_unlock.zip'
    data = bytearray(open(src, 'rb').read())
    sha = hashlib.sha256(data).hexdigest()
    if sha != EXPECT_SHA:
        print(f'警告：原库 sha256 = {sha}，与预期 {EXPECT_SHA} 不符，请先确认偏移 {hex(OFFSET)}')
    got = bytes(data[OFFSET:OFFSET + len(ORIG_BYTES)])
    if got != ORIG_BYTES:
        sys.exit(f'错误：偏移 {hex(OFFSET)} 处字节为 {got.hex()}，不是预期的 {ORIG_BYTES.hex()}')
    data[OFFSET:OFFSET + len(NEW_BYTES)] = NEW_BYTES
    here = os.path.dirname(os.path.abspath(__file__))
    stage = os.path.join(here, 'build')
    shutil.rmtree(stage, ignore_errors=True)
    os.makedirs(os.path.join(stage, os.path.dirname(DEST_REL)))
    open(os.path.join(stage, DEST_REL), 'wb').write(bytes(data))
    for f in ('module.prop', 'customize.sh'):
        shutil.copy(os.path.join(here, f), stage)
    with zipfile.ZipFile(out_zip, 'w', zipfile.ZIP_DEFLATED) as z:
        for root, _, files in os.walk(stage):
            for f in files:
                p = os.path.join(root, f)
                z.write(p, os.path.relpath(p, stage))
    print('补丁后 sha256:', hashlib.sha256(bytes(data)).hexdigest())
    print('模块 zip:', out_zip)

if __name__ == '__main__':
    main()
