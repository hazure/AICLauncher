import UnityPy

# 路径换成你实际的 level3 路径
path = "/data/data/com.termux/files/home/storage/shared/Documents/AliceInCradle_ver030/AliceInCradle_Data/level3"

print(f"Loading {path} ...")
env = UnityPy.load(path)

for obj in env.objects:
    obj_type = obj.type.name
    try:
        data = obj.read()
        name = getattr(data, 'm_Name', 'Unnamed')
    except Exception:
        name = 'Unreadable'
    
    print(f"Type: {obj_type:<15} PathID: {obj.path_id:<15} Name: {name}")
    
    # 如果类型是 TextAsset，打印前 200 字节内容，看是否包含报错信息
    if obj_type == "TextAsset":
        try:
            script = data.m_Script
            if "unsupported environment" in script:
                print(">>> FOUND TARGET STRING IN TEXTASSET <<<")
        except:
            pass
