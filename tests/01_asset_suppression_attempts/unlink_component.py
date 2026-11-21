import UnityPy

path = "/data/data/com.termux/files/home/storage/shared/Documents/AliceInCradle_ver030/AliceInCradle_Data/level3"
print(f"Loading {path} ...")
env = UnityPy.load(path)

# 找到 GameObject (ID 2)
go = None
for obj in env.objects:
    if obj.path_id == 2:
        go = obj.read()
        break

if not go:
    print("GameObject 2 not found!")
    exit(1)

print(f"Original components on GameObject 2: {go.m_Component}")

# 遍历它的组件列表，把指向 12 (MonoBehaviour) 的项删掉
# m_Component 是一个列表，里面的元素通常包含 component 和 classID 属性
new_components = []
for comp in go.m_Component:
    if hasattr(comp, 'component') and comp.component.path_id == 12:
        print("Found MonoBehaviour 12! Removing...")
        continue # 跳过它，达到移除效果
    new_components.append(comp)

go.m_Component = new_components
go.save()
print("GameObject 2 updated.")

with open(path, "wb") as f:
    f.write(env.file.save(packer="none"))
print("Done. File saved without the MonoBehaviour.")
