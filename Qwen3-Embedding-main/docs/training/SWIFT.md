# SWIFT Training Support

The Qwen3-Embedding series models can be further trained when needed (for example, when users have domain-specific data). This document describes how to start training using the [SWIFT framework](https://github.com/modelscope/swift).

ModelScope SWIFT is a large model and multimodal large model training and deployment framework provided by the ModelScope community, with the following characteristics:

- Model Types: Supports 500+ pure text large models and 200+ multimodal large models, covering the entire process from training to deployment.
- Hardware Support: Compatible with CPU, RTX series GPUs, T4/V100, A10/A100/H100, Ascend NPU, MPS, etc.
- Training Methods: Supports full-parameter fine-tuning, LoRA, QLoRA, DoRA, and other techniques.
- Distributed Training: Supports distributed training techniques such as DDP, device_map, DeepSpeed ZeRO-2/ZeRO-3, FSDP, and integrates Megatron's parallel techniques including tensor parallelism, pipeline parallelism, sequence parallelism, and expert parallelism.
- RLHF Training: Supports human alignment methods for both pure text and multimodal large models, such as DPO, GRPO, DAPO, RM, PPO, KTO, etc.

Before starting training, please ensure your environment is properly configured.

```bash
pip install ms-swift -U
# Install from source
pip install git+https://github.com/modelscope/ms-swift.git

pip install transformers -U

# Optional packages
pip install deepspeed # multi-GPU training
pip install liger-kernel # save GPU memory resources
pip install flash-attn --no-build-isolation
```

## Embedding Training

### Data Preparation

Different loss types affect the dataset input format. Using the following data format as an example:

```json
# sample without hard negatives
{"messages": [{"role": "user", "content": "sentence1"}], "positive_messages": [[{"role": "user", "content": "sentence2"}]]}
# sample with multiple hard negatives
{"messages": [{"role": "user", "content": "sentence1"}], "positive_messages": [[{"role": "user", "content": "sentence2"}]], "negative_messages": [[{"role": "user", "content": "sentence3"}], [{"role": "user", "content": "sentence4"}]]}
```

The above data format is used when the loss is `infonce`. This loss uses `positive_messages (positive)` and `messages (anchor)` for positive training, and uses `negative_messages (negatives)` for negative training.

### Loss Types

It is recommended to use infonce loss (`--loss_type infonce`) for training.
Infonce loss has several adjustable environment variables:

- INFONCE_TEMPERATURE: Temperature value for the infonce similarity matrix, default is `0.01`.
- INFONCE_USE_BATCH: Whether to use other batches (including samples from other GPUs during DDP) as negatives, default is `True`. If set to `False`, only the rejected_response of the current sample is used as negatives, requiring each sample in the dataset to have at least one negative sample.
- INFONCE_HARD_NEGATIVES: Pads negatives (repeated sampling) or truncates them to ensure the same number of negatives for each sample. Default is `False`.
- INFONCE_MASK_FAKE_NEGATIVE: If negatives exist with similarity greater than positive similarity + `0.1`, mask them to prevent false negatives. Default is `False`.

By default, each row in the dataset can have any number of negative_messages or none at all.

Other loss types can also be used for training, such as `--loss_type cosine_similarity`, where the dataset format is different:

```json
{"messages": [{"role": "user", "content": "sentence1"}], "positive_messages": [[{"role": "user", "content": "sentence2"}]], "label": 0.8}
```

Under this loss, the label field is a float type marking the similarity value between two sentences.

Other types of losses are also supported. A complete introduction to losses and data formats can be found [here](https://github.com/modelscope/ms-swift/blob/main/docs/source_en/BestPractices/Embedding.md).

### Complete Training Command

Using infonce loss as an example, the complete training command is as follows:

```shell
nproc_per_node=8
NPROC_PER_NODE=$nproc_per_node \
swift sft \
    --model Qwen/Qwen3-Embedding-0.6B \
    --task_type embedding \
    --model_type qwen3_emb \
    --train_type full \
    --dataset sentence-transformers/stsb:positive \
    --split_dataset_ratio 0.05 \
    --eval_strategy steps \
    --output_dir output \
    --eval_steps 20 \
    --num_train_epochs 5 \
    --save_steps 20 \
    --per_device_train_batch_size 4 \
    --per_device_eval_batch_size 4 \
    --gradient_accumulation_steps 4 \
    --learning_rate 6e-6 \
    --loss_type infonce \
    --label_names labels \
    --dataloader_drop_last true \
    --deepspeed zero3
```

## Reranker Training

### Data Preparation

The reranker training data format is similar to embedding training but focuses on ranking relationships between query-document pairs. Using the following data format as an example:

```json lines
{"messages": [{"role": "user", "content": "query"}], "positive_messages": [[{"role": "assistant", "content": "relevant_doc1"}],[{"role": "assistant", "content": "relevant_doc2"}]], "negative_messages": [[{"role": "assistant", "content": "irrelevant_doc1"}],[{"role": "assistant", "content": "irrelevant_doc2"}], ...]}
```

**Field Description:**
- `messages`: Query text
- `positive_messages`: List of positive documents relevant to the query, supports multiple positive examples
- `negative_messages`: List of negative documents irrelevant to the query, supports multiple negative examples

**Environment Variable Configuration:**
- `MAX_POSITIVE_SAMPLES`: Maximum number of positive examples per query (default: 1)
- `MAX_NEGATIVE_SAMPLES`: Maximum number of negative examples per query (default: 7)

> By default, `MAX_POSITIVE_SAMPLES` positive examples and `MAX_NEGATIVE_SAMPLES` negative examples will be extracted from each data item. Each positive example will be grouped with `MAX_NEGATIVE_SAMPLES` negative examples to form a group. Therefore, each data item will be expanded into `MAX_POSITIVE_SAMPLES`x`(1 + MAX_NEGATIVE_SAMPLES)` data points.
> If the number of positive/negative examples in the data is insufficient, all positive/negative examples will be used. If the number of positive and negative examples in the data exceeds `MAX_POSITIVE_SAMPLES` and `MAX_NEGATIVE_SAMPLES`, random sampling will be performed.
> **IMPORTANT**: The expanded data will be placed in the same batch. Therefore, the effective batch size on each device will be `per_device_train_batch_size` × `MAX_POSITIVE_SAMPLES` × (1 + `MAX_NEGATIVE_SAMPLES`). Please adjust your `per_device_train_batch_size` accordingly to avoid out-of-memory errors.

### Loss Types

SWIFT supports two loss types for reranker training: pointwise loss and listwise loss.

#### Pointwise Loss
Treats ranking as binary classification for each query-document pair:
- **Loss Function**: Binary cross-entropy
- **Approach**: Independent judgment for each pair
- **Environment Variables**:
  - `GENERATIVE_RERANKER_POSITIVE_TOKEN`: Positive token (default: "yes")
  - `GENERATIVE_RERANKER_NEGATIVE_TOKEN`: Negative token (default: "no")

#### Listwise Loss
Treats ranking as multi-classification among candidate documents:
- **Loss Function**: Multi-class cross-entropy
- **Approach**: Learn relative ranking relationships
- **Environment Variables**:
  - `LISTWISE_RERANKER_TEMPERATURE`: Listwise temperature (default: 1.0)
  - `LISTWISE_RERANKER_MIN_GROUP_SIZE`: Minimum group size (default: 2)

### Complete Training Command

Example for training a Qwen3-Reranker model using pointwise loss:

```shell
nproc_per_node=4
NPROC_PER_NODE=$nproc_per_node \
swift sft \
    --model Qwen/Qwen3-Reranker-4B \
    --task_type generative_reranker \
    --loss_type generative_reranker \
    --train_type full \
    --dataset MTEB/scidocs-reranking \
    --split_dataset_ratio 0.05 \
    --eval_strategy steps \
    --output_dir output \
    --eval_steps 100 \
    --num_train_epochs 1 \
    --save_steps 200 \
    --per_device_train_batch_size 2 \
    --per_device_eval_batch_size 2 \
    --gradient_accumulation_steps 8 \
    --learning_rate 6e-6 \
    --label_names labels \
    --dataloader_drop_last true
```

## Inference and Deployment

SWIFT now supports for both embedding and reranker models. Refer to the official examples for full options:
- Embedding example: [ms-swift/examples/deploy/embedding](https://github.com/modelscope/ms-swift/tree/main/examples/deploy/embedding)
- Reranker example: [ms-swift/examples/deploy/reranker](https://github.com/modelscope/ms-swift/tree/main/examples/deploy/reranker)
