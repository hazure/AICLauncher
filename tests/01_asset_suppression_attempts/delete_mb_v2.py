import UnityPy

path = "/data/data/com.termux/files/home/storage/shared/Documents/AliceInCradle_ver030/AliceInCradle_Data/level3"
print(f"Loading {path} ...")
env = UnityPy.load(path)

count_before = len(env.objects)

# UnityPy 的 env.file 包含了底层结构
# 我们通过操作 env.file.objects 这个真正的字典来移除 PathID 12
if hasattr(env, 'file') and hasattr(env.file, 'objects'):
    if 12 in env.file.objects:
        del env.file.objects[12]
        print("Successfully deleted PathID 12 from env.file.objects dictionary.")
    else:
        print("PathID 12 not found in env.file.objects (maybe it was already removed).")
else:
    print("Could not access env.file.objects")

count_after = len(env.objects)
print(f"Objects before: {count_before}, after: {count_after}")

with open(path, "wb") as f:
    f.write(env.file.save(packer="none"))
print("Done. File saved.")
