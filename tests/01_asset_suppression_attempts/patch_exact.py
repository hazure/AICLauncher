import UnityPy

path = "/data/data/com.termux/files/home/storage/shared/Documents/AliceInCradle_ver030/AliceInCradle_Data/level3"
print(f"Loading {path} ...")
env = UnityPy.load(path)

for obj in env.objects:
    if obj.path_id == 7: # TextMesh
        print("Patching TextMesh (7)...")
        data = obj.read()
        if hasattr(data, 'm_Text'):
            data.m_Text = ""
            data.save()
            print("TextMesh cleared.")
            
    if obj.path_id == 12: # MonoBehaviour
        print("Patching MonoBehaviour (12)...")
        data = obj.read()
        # 尝试把 MonoBehaviour 的启用状态关掉
        if hasattr(data, 'm_Enabled'):
            data.m_Enabled = False
            print("MonoBehaviour disabled.")
        # 遍历这个组件里的所有字符串字段，全部清空
        for attr in dir(data):
            if not attr.startswith('_'):
                try:
                    val = getattr(data, attr)
                    if isinstance(val, str) and len(val) > 0:
                        setattr(data, attr, "")
                        print(f"Cleared string field: {attr}")
                except:
                    pass
        data.save()

# 保存为不压缩的格式，防止 Unity 解析错误
with open(path, "wb") as f:
    f.write(env.file.save(packer="none"))
print("Done. File saved.")
