#!/system/bin/sh
# 安装时校验原库指纹，避免 ROM 版本不匹配导致补丁打错位置
ORIG=/system_ext/lib64/libSpecailizerPLService.so
EXPECT=b38a6d3507f5bbbb504b609540df3b458cb28faa24bc4ac67e72ef327113f5ea
GOT=$(sha256sum "$ORIG" 2>/dev/null | awk '{print $1}')
ui_print "libSpecailizerPLService.so sha256 = $GOT"
if [ "$GOT" != "$EXPECT" ]; then
  ui_print "!! 指纹与预期不符：本补丁只针对 b38a6d35… 这一版本"
  ui_print "!! 仍会安装，但请确认偏移 0x1b0b4 是否仍为 isVocalAdjustSupported"
fi
