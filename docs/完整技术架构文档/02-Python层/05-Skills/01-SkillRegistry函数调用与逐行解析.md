# Skills：SkillRegistry 函数调用与逐行解析

## 1. 接口定义

Skills 模块没有独立 FastAPI 路由。它由面试规划、路由、出题和简历评价 Agent 在进程内调用，职责是从 `python-agent/resources/skills` 加载受控元数据与 `SKILL.md`，校验工具白名单，并把可用技能提供给模型。外部请求只能传岗位、JD 和业务方向，不能直接注入 Skill 指令。

## 2. 函数调用链

```text
InterviewPlanner.create_plan
 -> available_for_interview -> public_catalog -> _validate_public_item -> get
 -> select_for_interview -> get
 -> selection_catalog -> available_for_interview
 -> resolve_for_interview -> get

InterviewEvaluationAgent.evaluate -> get("interview-coach")
InterviewRoutingAgent.route / InterviewQuestionAgent.generate -> resolve_for_interview -> get
ResumeEvaluationAgent.evaluate -> get("resume-analyst")
```

## 3. 函数解析

### 3.1 `SkillRegistry.__init__`

文件：`python-agent/app/tools/skills/loader.py:44-45`

```python
    def __init__(self, root: Path | None = None) -> None:
        self._root = root or PROJECT_DIR / "resources" / "skills"
```

逐行解释：

1. 第 44 行：允许测试传入替代根目录，生产环境可以不传。
2. 第 45 行：未传目录时固定使用项目资源目录，不从用户请求拼接根路径。

### 3.2 `SkillRegistry.get`

文件：`python-agent/app/tools/skills/loader.py:47-84`

```python
    def get(self, skill_id: str) -> SkillDefinition:
        if not isinstance(skill_id, str) or not re.fullmatch(
            r"[a-z0-9]+(?:-[a-z0-9]+)*", skill_id
        ):
            raise SkillConfigurationError(f"Skill ID 格式错误: {skill_id}")
        skill_dir = self._root / skill_id
        metadata_path = skill_dir / "skill.json"
        instruction_path = skill_dir / "SKILL.md"
        try:
            metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
            instructions = instruction_path.read_text(encoding="utf-8")
        except FileNotFoundError as error:
            raise SkillConfigurationError(f"Skill 文件不存在: {skill_id}") from error
        except json.JSONDecodeError as error:
            raise SkillConfigurationError(f"Skill 元数据格式错误: {skill_id}") from error

        if not metadata.get("enabled", True):
            raise SkillConfigurationError(f"Skill 未启用: {skill_id}")
        if metadata.get("id") != skill_id:
            raise SkillConfigurationError(f"Skill ID 不匹配: {skill_id}")
        raw_allowed_tools = metadata.get("allowedTools", [])
        if not isinstance(raw_allowed_tools, list) or not all(
            isinstance(tool, str) and tool.strip() for tool in raw_allowed_tools
        ):
            raise SkillConfigurationError(f"Skill allowedTools 格式错误: {skill_id}")
        unsupported_tools = set(raw_allowed_tools) - SUPPORTED_INTERVIEW_TOOLS
        if unsupported_tools:
            unsupported = ", ".join(sorted(unsupported_tools))
            raise SkillConfigurationError(
                f"Skill 声明了未实现的工具: {skill_id}: {unsupported}"
            )
        return SkillDefinition(
            skill_id=skill_id,
            name=str(metadata["name"]),
            description=str(metadata["description"]),
            instructions=instructions,
            allowed_tools=tuple(dict.fromkeys(raw_allowed_tools)),
        )
```

逐行解释：

1. 第 47-51 行：Skill ID 必须是小写字母数字及单个连字符分段，目录穿越字符无法通过正则。
2. 第 52-54 行：在固定根目录下定位技能目录、元数据和指令文件。
3. 第 55-58 行：按 UTF-8 读取 JSON 与 Markdown；缺文件转成明确配置错误。
4. 第 59-60 行：JSON 语法错误同样包装为 Skill 配置错误。
5. 第 62-65 行：拒绝 disabled 技能，并要求元数据 ID 与目录 ID 完全一致。
6. 第 66-70 行：读取 allowedTools，必须是由非空字符串组成的数组。
7. 第 71-76 行：声明工具减去运行时支持集合；只要有未实现工具就拒绝整个 Skill。
8. 第 77-84 行：把元数据、完整指令文本和去重后的工具序列构造成不可变 `SkillDefinition`。

### 3.3 `resolve_for_interview`

文件：`python-agent/app/tools/skills/loader.py:86-102`

```python
    def resolve_for_interview(self, skill_ids: list[str] | tuple[str, ...]) -> tuple[SkillDefinition, ...]:
        """Resolve persisted/model-selected IDs without letting a stale ID stop an interview."""
        resolved: list[SkillDefinition] = []
        seen: set[str] = set()
        for skill_id in skill_ids:
            if not isinstance(skill_id, str) or not skill_id.strip() or skill_id in seen:
                continue
            try:
                resolved.append(self.get(skill_id))
                seen.add(skill_id)
            except SkillConfigurationError:
                logger.warning("忽略不存在或无效的面试 Skill: %s", skill_id)
        if not resolved:
            resolved.append(self.get("interview-coach"))
        elif "interview-coach" not in seen:
            resolved.insert(0, self.get("interview-coach"))
        return tuple(resolved)
```

逐行解释：

1. 第 86-88 行：声明解析持久化或模型选择 ID 的函数，并创建结果和去重集合。
2. 第 90-92 行：逐个遍历，跳过非字符串、空值和重复 ID。
3. 第 93-97 行：调用 `get`；有效项加入结果，无效项只记录告警，不让旧会话中断。
4. 第 98-101 行：无任何有效项时使用 `interview-coach`；有其他项但缺基础技能时把它插到首位。
5. 第 102 行：返回不可变元组，调用方不能意外修改注册结果。

### 3.4 `available_for_interview`

文件：`python-agent/app/tools/skills/loader.py:104-116`

```python
    def available_for_interview(self) -> tuple[SkillDefinition, ...]:
        """Return only enabled Skills that are both advertised and installed."""
        skill_ids = ["interview-coach", *(
            str(item["id"]) for item in self.public_catalog()
        )]
        available: list[SkillDefinition] = []
        seen: set[str] = set()
        for skill_id in skill_ids:
            if skill_id in seen:
                continue
            available.append(self.get(skill_id))
            seen.add(skill_id)
        return tuple(available)
```

逐行解释：

1. 第 104-108 行：基础教练技能总在首位，再追加公开目录中的 ID。
2. 第 109-110 行：初始化有序结果和去重集合。
3. 第 111-115 行：跳过重复项，并通过 `get` 再验证文件确实可加载。
4. 第 116 行：以元组返回已启用、已展示且已安装技能。

### 3.5 `selection_catalog`

文件：`python-agent/app/tools/skills/loader.py:118-128`

```python
    def selection_catalog(self) -> list[dict[str, object]]:
        """Safe metadata exposed to the planning model before it selects Skills."""
        return [
            {
                "id": item.skill_id,
                "name": item.name,
                "description": item.description,
                "allowedTools": list(item.allowed_tools),
            }
            for item in self.available_for_interview()
        ]
```

逐行解释：

1. 第 118-120 行：定义给规划模型的安全目录并开始列表推导。
2. 第 121-126 行：每项只暴露 ID、名称、描述和允许工具，不暴露 `SKILL.md` 原始指令。
3. 第 127 行：数据源是经过完整验证的 `available_for_interview`。
4. 第 128 行：结束并返回列表。

### 3.6 `select_for_interview`

文件：`python-agent/app/tools/skills/loader.py:130-158`

```python
    def select_for_interview(
        self, *, target_role: str, jd_text: str | None, interview_direction: str | None = None
    ) -> tuple[SkillDefinition, ...]:
        """由下层 Agent 根据职位选择 Skill；上层只传递职位/JD 快照。"""
        normalized = f"{target_role}\n{jd_text or ''}".lower()
        selected = [self.get("interview-coach")]
        for skill_id in DIRECTION_SKILL_CANDIDATES.get(interview_direction or "", ()):
            if (self._root / skill_id).exists():
                selected.append(self.get(skill_id))
        domain_keywords = {
            "java-backend": ("java", "spring", "jvm"),
            "python-backend": ("python", "fastapi", "django", "flask"),
            "system-design": ("system design", "系统设计", "微服务", "分布式"),
            "algorithm": ("algorithm", "算法", "leetcode", "数据结构"),
            "computer-vision": (
                "computer vision", "计算机视觉", "opencv", "目标检测", "图像处理",
                "图像分割", "语义分割", "ocr", "yolo", "视觉算法",
            ),
        }
        for skill_id, keywords in domain_keywords.items():
            skill_dir = self._root / skill_id
            if skill_dir.exists() and any(keyword in normalized for keyword in keywords):
                selected.append(self.get(skill_id))
        unique: dict[str, SkillDefinition] = {item.skill_id: item for item in selected}
        return tuple(unique.values())
```

逐行解释：

1. 第 130-133 行：要求关键字参数，返回技能定义元组。
2. 第 134-136 行：把岗位与 JD 小写拼接，初始选择固定包含教练技能。
3. 第 137-139 行：业务方向只映射到注册表自有候选；对应目录存在才加载。
4. 第 140-149 行：声明各领域确定性关键词，包括中英文和常见框架词。
5. 第 150-153 行：只对已安装目录匹配；任一关键词命中才加载对应 Skill。
6. 第 154-158 行：按 skill_id 构造有序字典去重，并返回元组。

### 3.7 `public_catalog`

文件：`python-agent/app/tools/skills/loader.py:160-178`

```python
    def public_catalog(self) -> list[dict[str, object]]:
        """读取供上层展示的 Skill 目录；不暴露 SKILL.md 的内部指令。"""
        catalog_path = self._root / "catalog.json"
        try:
            catalog = json.loads(catalog_path.read_text(encoding="utf-8"))
        except FileNotFoundError as error:
            raise SkillConfigurationError("Skill 展示目录不存在") from error
        except json.JSONDecodeError as error:
            raise SkillConfigurationError("Skill 展示目录格式错误") from error
        if not isinstance(catalog, list):
            raise SkillConfigurationError("Skill 展示目录必须是数组")
        validated = [self._validate_public_item(item) for item in catalog]
        catalog_ids = [str(item["id"]) for item in validated]
        if len(catalog_ids) != len(set(catalog_ids)):
            raise SkillConfigurationError("Skill 展示目录包含重复 ID")
        for item in validated:
            self.get(str(item["id"]))
        return validated
```

逐行解释：

1. 第 160-162 行：目录固定为技能根目录下的 `catalog.json`。
2. 第 163-168 行：读取并解析 UTF-8 JSON；缺失和语法错误分别包装。
3. 第 169-170 行：根值必须是数组。
4. 第 171 行：逐项调用 `_validate_public_item`。
5. 第 172-174 行：提取 ID 并拒绝重复。
6. 第 175-176 行：每个展示项还必须能由 `get` 实际加载，防止展示不存在能力。
7. 第 177 行：返回验证后的公开元数据。

### 3.8 `categories_for_jd`

文件：`python-agent/app/tools/skills/loader.py:180-211`

```python
    def categories_for_jd(self, jd_text: str) -> list[dict[str, object]]:
        """用外置关键词从 JD 提取展示分类；这是确定性预处理，不调用模型。"""
        normalized = jd_text.strip().lower()
        if not normalized:
            return []
        catalog = self.public_catalog()
        candidates = [
            category
            for item in catalog
            for category in item["categories"]
            if isinstance(category, dict)
        ]
        matched = [
            category
            for category in candidates
            if any(
                str(keyword).lower() in normalized
                for keyword in category.get("keywords", [])
            )
        ]
        selected = matched or candidates
        return [
            {
                "key": category["key"],
                "label": category["label"],
                "priority": category["priority"],
                "ref": category.get("ref"),
                "shared": category.get("shared", False),
            }
            for category in selected
        ]
```

逐行解释：

1. 第 180-184 行：去空白并转小写；空 JD 直接返回空列表。
2. 第 185 行：读取经过验证的公开目录。
3. 第 186-191 行：展开所有技能的字典型分类，忽略类型异常项。
4. 第 192-199 行：只要分类任一关键词出现在 JD 中就匹配。
5. 第 200 行：有匹配用匹配项，没有匹配则返回全部候选作为兜底。
6. 第 201-211 行：输出 key、label、priority、ref、shared；关键词等内部匹配数据不返回。

### 3.9 `_validate_public_item`

文件：`python-agent/app/tools/skills/loader.py:213-221`

```python
    @staticmethod
    def _validate_public_item(item: object) -> dict[str, object]:
        if not isinstance(item, dict):
            raise SkillConfigurationError("Skill 展示项必须是对象")
        required = {"id", "name", "description", "categories", "isPreset"}
        if not required.issubset(item):
            raise SkillConfigurationError("Skill 展示项缺少必要字段")
        if not isinstance(item["categories"], list):
            raise SkillConfigurationError("Skill 分类必须是数组")
        return dict(item)
```

逐行解释：

1. 第 213-214 行：声明无实例状态的静态验证函数。
2. 第 215-216 行：展示项必须是字典。
3. 第 217-219 行：必须具备五个规定字段。
4. 第 220-221 行：categories 必须是数组；通过后复制为新字典，避免返回原对象引用。

## 4. 审核结论

Skills 模块全部九个项目函数均已给出源码、文件行号和逐行语义。模型只能在运行时目录允许的技能集合内选择，且当前唯一支持的声明式工具是 `rag.search`。
