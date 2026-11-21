import UnityPy
import os

path = "/data/data/com.termux/files/home/storage/shared/Documents/AliceInCradle_ver030/AliceInCradle_Data/level3"
print(f"Loading {path} ...")
env = UnityPy.load(path)

count_before = len(env.objects)

# 直接修改底层的 objects 字典，把 PathID 为 12 的对象的引用干掉
# 注意：不同版本的 UnityPy，objects 有可能是 dict 或者特殊的列表结构
if hasattr(env, 'objects') and isinstance(env.objects, dict):
    if 12 in env.objects:
        del env.objects[12]
        print("Deleted PathID 12 from dict.")
else:
    # 如果是旧的列表形式
    env.objects = [obj for obj in env.objects if obj.path_id != 12]
    print("Filtered out PathID 12 from list.")

count_after = len(env.objects)
print(f"Objects before: {count_before}, after: {count_after}")

with open(path, "wb") as f:
    f.write(env.file.save(packer="none"))
print("Done. File saved.")
