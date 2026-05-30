import pytest

from tool_lab_strands_client.main import (
    DEFAULT_MCP_URL,
    DEFAULT_MODEL_ID,
    DEFAULT_MODEL_PROVIDER,
    DEFAULT_QWEN_BASE_URL,
    DEFAULT_QWEN_MODEL_ID,
    DEFAULT_REGION,
    build_model,
    load_config,
)


def test_load_config_uses_defaults() -> None:
    config = load_config({})

    assert config.mcp_url == DEFAULT_MCP_URL
    assert config.model_provider == DEFAULT_MODEL_PROVIDER
    assert config.aws_region == DEFAULT_REGION
    assert config.model_id == DEFAULT_MODEL_ID
    assert config.aws_profile is None
    assert config.qwen_base_url == DEFAULT_QWEN_BASE_URL
    assert config.qwen_model_id == DEFAULT_QWEN_MODEL_ID
    assert config.qwen_api_key == "dummy"
    assert config.qwen_timeout_seconds == 120
    assert config.qwen_enable_thinking is False
    assert config.qwen_preserve_thinking is False


def test_load_config_reads_environment_overrides() -> None:
    config = load_config(
        {
            "TOOL_LAB_MCP_URL": "http://localhost:9999/mcp",
            "AWS_REGION": "us-west-2",
            "BEDROCK_GROUNDING_MODEL_ID": "anthropic.example",
            "AWS_PROFILE": "tool-lab-profile",
            "TOOL_LAB_MODEL_PROVIDER": "qwen",
            "QWEN_OPENAI_BASE_URL": "http://localhost:18000/v1",
            "QWEN_MODEL": "qwen-example",
            "QWEN_OPENAI_API_KEY": "token",
            "QWEN_OPENAI_TIMEOUT_SECONDS": "90",
            "QWEN_ENABLE_THINKING": "true",
            "QWEN_PRESERVE_THINKING": "true",
        }
    )

    assert config.mcp_url == "http://localhost:9999/mcp"
    assert config.model_provider == "qwen"
    assert config.aws_region == "us-west-2"
    assert config.model_id == "anthropic.example"
    assert config.aws_profile == "tool-lab-profile"
    assert config.qwen_base_url == "http://localhost:18000/v1"
    assert config.qwen_model_id == "qwen-example"
    assert config.qwen_api_key == "token"
    assert config.qwen_timeout_seconds == 90
    assert config.qwen_enable_thinking is True
    assert config.qwen_preserve_thinking is True


def test_build_model_rejects_unknown_provider() -> None:
    config = load_config({"TOOL_LAB_MODEL_PROVIDER": "bad"})

    with pytest.raises(ValueError, match="Unknown model provider"):
        build_model(config)


def test_build_model_uses_qwen_by_default() -> None:
    model = build_model(load_config({}))

    config = model.get_config()
    assert config["model_id"] == DEFAULT_QWEN_MODEL_ID
    assert config["params"]["temperature"] == 0.0
    assert config["params"]["max_tokens"] == 1024


def test_build_model_uses_bedrock_when_provider_set() -> None:
    model = build_model(
        load_config(
            {
                "TOOL_LAB_MODEL_PROVIDER": "bedrock",
                "BEDROCK_GROUNDING_MODEL_ID": "us.anthropic.claude-sonnet-4-6",
                "AWS_REGION": "us-east-2",
            }
        )
    )

    config = model.get_config()
    assert config["model_id"] == "us.anthropic.claude-sonnet-4-6"
    assert config["temperature"] == 0.0
    assert config["max_tokens"] == 1024


def test_build_model_uses_openai_compatible_qwen_provider() -> None:
    model = build_model(
        load_config(
            {
                "TOOL_LAB_MODEL_PROVIDER": "qwen",
                "QWEN_OPENAI_BASE_URL": "http://localhost:18000/v1",
                "QWEN_MODEL": "qwen-example",
                "QWEN_OPENAI_API_KEY": "token",
                "QWEN_OPENAI_TIMEOUT_SECONDS": "90",
            }
        )
    )

    config = model.get_config()
    assert config["model_id"] == "qwen-example"
    assert config["params"] == {
        "temperature": 0.0,
        "max_tokens": 1024,
        "extra_body": {
            "chat_template_kwargs": {
                "enable_thinking": False,
                "preserve_thinking": False,
            },
        },
    }
    assert model.client_args["base_url"] == "http://localhost:18000/v1"
    assert model.client_args["api_key"] == "token"
    assert model.client_args["timeout"] == 90
