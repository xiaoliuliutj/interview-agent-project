"""下层异常类型和 Python 内置异常到统一错误结构的映射。"""

from collections.abc import Mapping

from .contracts import ErrorInfo


class ApplicationException(Exception):
    """下层可预期的业务或工程异常。"""

    code: int = 500
    error_type: str = "APPLICATION_ERROR"
    retryable: bool = False

    def __init__(self, message: str, *, retryable: bool | None = None) -> None:
        super().__init__(message)
        self.message = message
        if retryable is not None:
            self.retryable = retryable


class RequestError(ApplicationException):
    code = 200
    error_type = "INVALID_REQUEST"


class ConsistencyError(ApplicationException):
    code = 300
    error_type = "SESSION_CONSISTENCY_ERROR"


class RateLimitError(ApplicationException):
    code = 400
    error_type = "RATE_LIMITED"
    retryable = True


class AgentDependencyError(ApplicationException):
    code = 500
    error_type = "AGENT_DEPENDENCY_ERROR"
    retryable = True


class ModelOutputError(ApplicationException):
    """模型已正常响应，但内容无法满足受控输出契约。"""

    code = 502
    error_type = "MODEL_OUTPUT_INVALID"
    retryable = False


class ModelConfigurationError(AgentDependencyError):
    code = 500
    error_type = "MODEL_CONFIGURATION_ERROR"
    retryable = False


class PersistenceConfigurationError(ApplicationException):
    code = 500
    error_type = "PERSISTENCE_CONFIGURATION_ERROR"
    retryable = False


class PromptConfigurationError(ApplicationException):
    code = 500
    error_type = "PROMPT_CONFIGURATION_ERROR"
    retryable = False


class SkillConfigurationError(ApplicationException):
    code = 500
    error_type = "SKILL_CONFIGURATION_ERROR"
    retryable = False


class WorkflowConfigurationError(ApplicationException):
    code = 500
    error_type = "WORKFLOW_CONFIGURATION_ERROR"
    retryable = False


class RagConfigurationError(ApplicationException):
    code = 500
    error_type = "RAG_CONFIGURATION_ERROR"
    retryable = False


class RagDependencyError(ApplicationException):
    code = 503
    error_type = "RAG_DEPENDENCY_ERROR"
    retryable = True


class RagFilterUnsupported(Exception):
    """底层向量仓库不支持 metadata 过滤时触发本地回退。"""


class ReliabilityConfigurationError(ApplicationException):
    code = 500
    error_type = "RELIABILITY_CONFIGURATION_ERROR"
    retryable = False


class ExceptionHandler:
    """将自定义异常和 Python 内置异常转换为统一错误信息。"""

    _builtin_mapping: Mapping[type[BaseException], tuple[int, str, bool]] = {
        ValueError: (200, "INVALID_VALUE", False),
        TypeError: (200, "INVALID_TYPE", False),
        KeyError: (300, "STATE_KEY_NOT_FOUND", False),
        LookupError: (300, "STATE_LOOKUP_FAILED", False),
        TimeoutError: (501, "MODEL_TIMEOUT", True),
        ConnectionError: (503, "DEPENDENCY_NETWORK_ERROR", True),
    }

    @classmethod
    def to_error_info(cls, error: BaseException) -> ErrorInfo:
        if isinstance(error, ApplicationException):
            return ErrorInfo(
                type=error.error_type,
                message=error.message,
                retryable=error.retryable,
            )

        for error_type, (code, error_name, retryable) in cls._builtin_mapping.items():
            if isinstance(error, error_type):
                return ErrorInfo(
                    type=error_name,
                    message=str(error) or error_name,
                    retryable=retryable,
                )

        return ErrorInfo(
            type="INTERNAL_ERROR",
            message="下层服务发生未预期异常",
            retryable=False,
        )

    @classmethod
    def to_code(cls, error: BaseException) -> int:
        if isinstance(error, ApplicationException):
            return error.code
        for error_type, (code, _, _) in cls._builtin_mapping.items():
            if isinstance(error, error_type):
                return code
        return 500
