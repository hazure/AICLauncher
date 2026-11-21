import UnityPy

path = "/data/data/com.termux/files/home/storage/shared/Documents/AliceInCradle_ver030/AliceInCradle_Data/level3"
backup_path = path + ".bak"

print(f"Loading {path} ...")
env = UnityPy.load(path)

# 我们要找到 PathID 为 12 的 MonoBehaviour，以及挂载它的 GameObject (ID 2)
target_mb = None
target_go = None

for obj in env.objects:
    if obj.path_id == 12:
        target_mb = obj
    elif obj.path_id == 2:
        target_go = obj

if target_mb is None:
    print("Error: MonoBehaviour 12 not found!")
    exit(1)

# 方法 1：直接标记该对象为已销毁（或者从 objects 列表移除）
print("Removing MonoBehaviour (PathID 12) from scene...")
# UnityPy 允许我们直接修改 objects 字典
# 注意：不同版本 API 可能不同，这里使用最兼容的移除方式
if 12 in env.objects:
    # 将 MonoBehaviour 替换为空对象或直接删除引用
    # 但为了安全，我们尝试修改它的 m_Enabled 字段为 0 (如果存在)
    try:
        mb_data = target_mb.read()
        if hasattr(mb_data, 'm_Enabled'):
            mb_data.m_Enabled = 0
            mb_data.save()
            print("Set m_Enabled = 0 on MonoBehaviour 12.")
        else:
            print("m_Enabled not found, trying to remove object entirely...")
            # 如果没有 m_Enabled，尝试从 GameObject 的组件列表中移除它
            pass
    except Exception as e:
        print(f"Failed to patch: {e}")

# 保存修改
print("Saving...")
with open(path, "wb") as f:
    f.write(env.file.save(packer="original"))
print("Done.")
