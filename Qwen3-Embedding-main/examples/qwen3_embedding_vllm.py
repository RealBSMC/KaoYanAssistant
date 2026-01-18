#coding:utf8
from typing import Dict, Optional, List, Union
import torch
import vllm
from vllm import LLM, PoolingParams
from vllm.distributed.parallel_state import destroy_model_parallel

class Qwen3EmbeddingVllm():
    def __init__(self, model_name_or_path, instruction=None, max_length=8192):
        if instruction is None:
            instruction = 'Given a web search query, retrieve relevant passages that answer the query'
        self.instruction = instruction
        self.model = LLM(model=model_name_or_path, task="embed", hf_overrides={"is_matryoshka": True})

    def get_detailed_instruct(self, task_description: str, query: str) -> str:
        if task_description is None:
            task_description = self.instruction
        return f'Instruct: {task_description}\nQuery:{query}'

    def encode(self, sentences: Union[List[str], str], is_query: bool = False, instruction=None, dim: int = -1):
        if isinstance(sentences, str):
            sentences = [sentences]
        if is_query:
            sentences = [self.get_detailed_instruct(instruction, sent) for sent in sentences]
        if dim > 0:
            output = self.model.embed(sentences,pooling_params=PoolingParams(dimensions=dim))
        else:
            output = self.model.embed(sentences)
        output = torch.tensor([o.outputs.embedding for o in output])
        return output


    def stop(self):
        destroy_model_parallel()

if __name__ == "__main__":
    model_path = "Qwen/Qwen3-Embedding-0.6B"
    model = Qwen3EmbeddingVllm(model_path)
    queries = ['What is the capital of China?', 'Explain gravity']
    documents = [
        "The capital of China is Beijing.",
        "Gravity is a force that attracts two bodies towards each other. It gives weight to physical objects and is responsible for the movement of planets around the sun."
    ]
    dim = 1024
    query_outputs = model.encode(queries, is_query=True, dim=dim)
    doc_outputs = model.encode(documents, dim=dim)
    print('query outputs', query_outputs)
    print('doc outputs', doc_outputs)
    scores = (query_outputs @ doc_outputs.T) * 100
    print(scores.tolist())
    model.stop()
