---
language:
- en
license: apache-2.0
base_model:
- RikkaBotan/stable-static-embedding-fast-retrieval-mrl-en
tags:
- sentence-transformers
- sentence-similarity
- feature-extraction
- loss:MatryoshkaLoss
- loss:MultipleNegativesRankingLoss
datasets:
- sentence-transformers/squad
- sentence-transformers/trivia-qa-triplet
- sentence-transformers/all-nli
- sentence-transformers/pubmedqa
- sentence-transformers/hotpotqa
- sentence-transformers/miracl
- sentence-transformers/mr-tydi
- sentence-transformers/s2orc
- nthakur/swim-ir-monolingual
- sentence-transformers/paq
- tomaarsen/natural-questions-hard-negatives
pipeline_tag: sentence-similarity
library_name: sentence-transformers
metrics:
- cosine_accuracy@1
- cosine_accuracy@3
- cosine_accuracy@5
- cosine_accuracy@10
- cosine_precision@1
- cosine_precision@3
- cosine_precision@5
- cosine_precision@10
- cosine_recall@1
- cosine_recall@3
- cosine_recall@5
- cosine_recall@10
- cosine_ndcg@10
- cosine_mrr@10
- cosine_map@100
model-index:
- name: Quantized SSE Retrieval MRL
  results:
  - task:
      type: information-retrieval
      name: Information Retrieval
    dataset:
      name: NanoClimateFEVER
      type: NanoClimateFEVER
    metrics:
    - type: cosine_accuracy@1
      value: 0.24
      name: Cosine Accuracy@1
    - type: cosine_accuracy@3
      value: 0.48
      name: Cosine Accuracy@3
    - type: cosine_accuracy@5
      value: 0.54
      name: Cosine Accuracy@5
    - type: cosine_accuracy@10
      value: 0.7
      name: Cosine Accuracy@10
    - type: cosine_precision@1
      value: 0.24
      name: Cosine Precision@1
    - type: cosine_precision@3
      value: 0.1733333333333333
      name: Cosine Precision@3
    - type: cosine_precision@5
      value: 0.13599999999999998
      name: Cosine Precision@5
    - type: cosine_precision@10
      value: 0.10400000000000001
      name: Cosine Precision@10
    - type: cosine_recall@1
      value: 0.11666666666666665
      name: Cosine Recall@1
    - type: cosine_recall@3
      value: 0.2366666666666667
      name: Cosine Recall@3
    - type: cosine_recall@5
      value: 0.285
      name: Cosine Recall@5
    - type: cosine_recall@10
      value: 0.4073333333333333
      name: Cosine Recall@10
    - type: cosine_ndcg@10
      value: 0.3126623923078016
      name: Cosine Ndcg@10
    - type: cosine_mrr@10
      value: 0.3822142857142857
      name: Cosine Mrr@10
    - type: cosine_map@100
      value: 0.24385537662727466
      name: Cosine Map@100
  - task:
      type: information-retrieval
      name: Information Retrieval
    dataset:
      name: NanoDBPedia
      type: NanoDBPedia
    metrics:
    - type: cosine_accuracy@1
      value: 0.66
      name: Cosine Accuracy@1
    - type: cosine_accuracy@3
      value: 0.82
      name: Cosine Accuracy@3
    - type: cosine_accuracy@5
      value: 0.82
      name: Cosine Accuracy@5
    - type: cosine_accuracy@10
      value: 0.92
      name: Cosine Accuracy@10
    - type: cosine_precision@1
      value: 0.66
      name: Cosine Precision@1
    - type: cosine_precision@3
      value: 0.56
      name: Cosine Precision@3
    - type: cosine_precision@5
      value: 0.504
      name: Cosine Precision@5
    - type: cosine_precision@10
      value: 0.442
      name: Cosine Precision@10
    - type: cosine_recall@1
      value: 0.0797501302985463
      name: Cosine Recall@1
    - type: cosine_recall@3
      value: 0.16262972268179665
      name: Cosine Recall@3
    - type: cosine_recall@5
      value: 0.2045520794896664
      name: Cosine Recall@5
    - type: cosine_recall@10
      value: 0.3019949956845787
      name: Cosine Recall@10
    - type: cosine_ndcg@10
      value: 0.547230935235287
      name: Cosine Ndcg@10
    - type: cosine_mrr@10
      value: 0.7439682539682541
      name: Cosine Mrr@10
    - type: cosine_map@100
      value: 0.4252227859210466
      name: Cosine Map@100
  - task:
      type: information-retrieval
      name: Information Retrieval
    dataset:
      name: NanoFEVER
      type: NanoFEVER
    metrics:
    - type: cosine_accuracy@1
      value: 0.48
      name: Cosine Accuracy@1
    - type: cosine_accuracy@3
      value: 0.76
      name: Cosine Accuracy@3
    - type: cosine_accuracy@5
      value: 0.78
      name: Cosine Accuracy@5
    - type: cosine_accuracy@10
      value: 0.92
      name: Cosine Accuracy@10
    - type: cosine_precision@1
      value: 0.48
      name: Cosine Precision@1
    - type: cosine_precision@3
      value: 0.25333333333333335
      name: Cosine Precision@3
    - type: cosine_precision@5
      value: 0.16399999999999998
      name: Cosine Precision@5
    - type: cosine_precision@10
      value: 0.09599999999999997
      name: Cosine Precision@10
    - type: cosine_recall@1
      value: 0.45666666666666667
      name: Cosine Recall@1
    - type: cosine_recall@3
      value: 0.7166666666666667
      name: Cosine Recall@3
    - type: cosine_recall@5
      value: 0.7566666666666667
      name: Cosine Recall@5
    - type: cosine_recall@10
      value: 0.8866666666666667
      name: Cosine Recall@10
    - type: cosine_ndcg@10
      value: 0.6870344519295848
      name: Cosine Ndcg@10
    - type: cosine_mrr@10
      value: 0.640190476190476
      name: Cosine Mrr@10
    - type: cosine_map@100
      value: 0.6190817417876242
      name: Cosine Map@100
  - task:
      type: information-retrieval
      name: Information Retrieval
    dataset:
      name: NanoFiQA2018
      type: NanoFiQA2018
    metrics:
    - type: cosine_accuracy@1
      value: 0.3
      name: Cosine Accuracy@1
    - type: cosine_accuracy@3
      value: 0.48
      name: Cosine Accuracy@3
    - type: cosine_accuracy@5
      value: 0.58
      name: Cosine Accuracy@5
    - type: cosine_accuracy@10
      value: 0.64
      name: Cosine Accuracy@10
    - type: cosine_precision@1
      value: 0.3
      name: Cosine Precision@1
    - type: cosine_precision@3
      value: 0.22666666666666666
      name: Cosine Precision@3
    - type: cosine_precision@5
      value: 0.17600000000000002
      name: Cosine Precision@5
    - type: cosine_precision@10
      value: 0.10400000000000001
      name: Cosine Precision@10
    - type: cosine_recall@1
      value: 0.1761904761904762
      name: Cosine Recall@1
    - type: cosine_recall@3
      value: 0.3212936507936508
      name: Cosine Recall@3
    - type: cosine_recall@5
      value: 0.3904603174603174
      name: Cosine Recall@5
    - type: cosine_recall@10
      value: 0.4646825396825397
      name: Cosine Recall@10
    - type: cosine_ndcg@10
      value: 0.3749515445946793
      name: Cosine Ndcg@10
    - type: cosine_mrr@10
      value: 0.4155238095238094
      name: Cosine Mrr@10
    - type: cosine_map@100
      value: 0.31291569954173215
      name: Cosine Map@100
  - task:
      type: information-retrieval
      name: Information Retrieval
    dataset:
      name: NanoHotpotQA
      type: NanoHotpotQA
    metrics:
    - type: cosine_accuracy@1
      value: 0.62
      name: Cosine Accuracy@1
    - type: cosine_accuracy@3
      value: 0.9
      name: Cosine Accuracy@3
    - type: cosine_accuracy@5
      value: 0.92
      name: Cosine Accuracy@5
    - type: cosine_accuracy@10
      value: 0.96
      name: Cosine Accuracy@10
    - type: cosine_precision@1
      value: 0.62
      name: Cosine Precision@1
    - type: cosine_precision@3
      value: 0.4333333333333333
      name: Cosine Precision@3
    - type: cosine_precision@5
      value: 0.288
      name: Cosine Precision@5
    - type: cosine_precision@10
      value: 0.158
      name: Cosine Precision@10
    - type: cosine_recall@1
      value: 0.31
      name: Cosine Recall@1
    - type: cosine_recall@3
      value: 0.65
      name: Cosine Recall@3
    - type: cosine_recall@5
      value: 0.72
      name: Cosine Recall@5
    - type: cosine_recall@10
      value: 0.79
      name: Cosine Recall@10
    - type: cosine_ndcg@10
      value: 0.6926740999438423
      name: Cosine Ndcg@10
    - type: cosine_mrr@10
      value: 0.7572222222222222
      name: Cosine Mrr@10
    - type: cosine_map@100
      value: 0.6205307600956663
      name: Cosine Map@100
  - task:
      type: information-retrieval
      name: Information Retrieval
    dataset:
      name: NanoMSMARCO
      type: NanoMSMARCO
    metrics:
    - type: cosine_accuracy@1
      value: 0.24
      name: Cosine Accuracy@1
    - type: cosine_accuracy@3
      value: 0.42
      name: Cosine Accuracy@3
    - type: cosine_accuracy@5
      value: 0.52
      name: Cosine Accuracy@5
    - type: cosine_accuracy@10
      value: 0.6
      name: Cosine Accuracy@10
    - type: cosine_precision@1
      value: 0.24
      name: Cosine Precision@1
    - type: cosine_precision@3
      value: 0.13999999999999999
      name: Cosine Precision@3
    - type: cosine_precision@5
      value: 0.10400000000000001
      name: Cosine Precision@5
    - type: cosine_precision@10
      value: 0.06000000000000001
      name: Cosine Precision@10
    - type: cosine_recall@1
      value: 0.24
      name: Cosine Recall@1
    - type: cosine_recall@3
      value: 0.42
      name: Cosine Recall@3
    - type: cosine_recall@5
      value: 0.52
      name: Cosine Recall@5
    - type: cosine_recall@10
      value: 0.6
      name: Cosine Recall@10
    - type: cosine_ndcg@10
      value: 0.41046676017842115
      name: Cosine Ndcg@10
    - type: cosine_mrr@10
      value: 0.3504126984126984
      name: Cosine Mrr@10
    - type: cosine_map@100
      value: 0.3694165354439681
      name: Cosine Map@100
  - task:
      type: information-retrieval
      name: Information Retrieval
    dataset:
      name: NanoNFCorpus
      type: NanoNFCorpus
    metrics:
    - type: cosine_accuracy@1
      value: 0.4
      name: Cosine Accuracy@1
    - type: cosine_accuracy@3
      value: 0.54
      name: Cosine Accuracy@3
    - type: cosine_accuracy@5
      value: 0.6
      name: Cosine Accuracy@5
    - type: cosine_accuracy@10
      value: 0.76
      name: Cosine Accuracy@10
    - type: cosine_precision@1
      value: 0.4
      name: Cosine Precision@1
    - type: cosine_precision@3
      value: 0.34
      name: Cosine Precision@3
    - type: cosine_precision@5
      value: 0.284
      name: Cosine Precision@5
    - type: cosine_precision@10
      value: 0.24999999999999992
      name: Cosine Precision@10
    - type: cosine_recall@1
      value: 0.0438546319021278
      name: Cosine Recall@1
    - type: cosine_recall@3
      value: 0.06454564661695922
      name: Cosine Recall@3
    - type: cosine_recall@5
      value: 0.07639519890924089
      name: Cosine Recall@5
    - type: cosine_recall@10
      value: 0.11843681136784412
      name: Cosine Recall@10
    - type: cosine_ndcg@10
      value: 0.30633384900526645
      name: Cosine Ndcg@10
    - type: cosine_mrr@10
      value: 0.4989365079365079
      name: Cosine Mrr@10
    - type: cosine_map@100
      value: 0.11484399038103764
      name: Cosine Map@100
  - task:
      type: information-retrieval
      name: Information Retrieval
    dataset:
      name: NanoNQ
      type: NanoNQ
    metrics:
    - type: cosine_accuracy@1
      value: 0.24
      name: Cosine Accuracy@1
    - type: cosine_accuracy@3
      value: 0.52
      name: Cosine Accuracy@3
    - type: cosine_accuracy@5
      value: 0.6
      name: Cosine Accuracy@5
    - type: cosine_accuracy@10
      value: 0.68
      name: Cosine Accuracy@10
    - type: cosine_precision@1
      value: 0.24
      name: Cosine Precision@1
    - type: cosine_precision@3
      value: 0.1733333333333333
      name: Cosine Precision@3
    - type: cosine_precision@5
      value: 0.12000000000000002
      name: Cosine Precision@5
    - type: cosine_precision@10
      value: 0.07200000000000001
      name: Cosine Precision@10
    - type: cosine_recall@1
      value: 0.23
      name: Cosine Recall@1
    - type: cosine_recall@3
      value: 0.5
      name: Cosine Recall@3
    - type: cosine_recall@5
      value: 0.58
      name: Cosine Recall@5
    - type: cosine_recall@10
      value: 0.67
      name: Cosine Recall@10
    - type: cosine_ndcg@10
      value: 0.4522511370072128
      name: Cosine Ndcg@10
    - type: cosine_mrr@10
      value: 0.38841269841269843
      name: Cosine Mrr@10
    - type: cosine_map@100
      value: 0.39410811050429245
      name: Cosine Map@100
  - task:
      type: information-retrieval
      name: Information Retrieval
    dataset:
      name: NanoQuoraRetrieval
      type: NanoQuoraRetrieval
    metrics:
    - type: cosine_accuracy@1
      value: 0.88
      name: Cosine Accuracy@1
    - type: cosine_accuracy@3
      value: 0.98
      name: Cosine Accuracy@3
    - type: cosine_accuracy@5
      value: 0.98
      name: Cosine Accuracy@5
    - type: cosine_accuracy@10
      value: 1.0
      name: Cosine Accuracy@10
    - type: cosine_precision@1
      value: 0.88
      name: Cosine Precision@1
    - type: cosine_precision@3
      value: 0.37999999999999995
      name: Cosine Precision@3
    - type: cosine_precision@5
      value: 0.23599999999999993
      name: Cosine Precision@5
    - type: cosine_precision@10
      value: 0.12199999999999997
      name: Cosine Precision@10
    - type: cosine_recall@1
      value: 0.7906666666666666
      name: Cosine Recall@1
    - type: cosine_recall@3
      value: 0.932
      name: Cosine Recall@3
    - type: cosine_recall@5
      value: 0.9453333333333334
      name: Cosine Recall@5
    - type: cosine_recall@10
      value: 0.9586666666666668
      name: Cosine Recall@10
    - type: cosine_ndcg@10
      value: 0.9147468671647309
      name: Cosine Ndcg@10
    - type: cosine_mrr@10
      value: 0.9222222222222223
      name: Cosine Mrr@10
    - type: cosine_map@100
      value: 0.894446226149392
      name: Cosine Map@100
  - task:
      type: information-retrieval
      name: Information Retrieval
    dataset:
      name: NanoSCIDOCS
      type: NanoSCIDOCS
    metrics:
    - type: cosine_accuracy@1
      value: 0.46
      name: Cosine Accuracy@1
    - type: cosine_accuracy@3
      value: 0.66
      name: Cosine Accuracy@3
    - type: cosine_accuracy@5
      value: 0.7
      name: Cosine Accuracy@5
    - type: cosine_accuracy@10
      value: 0.74
      name: Cosine Accuracy@10
    - type: cosine_precision@1
      value: 0.46
      name: Cosine Precision@1
    - type: cosine_precision@3
      value: 0.3133333333333333
      name: Cosine Precision@3
    - type: cosine_precision@5
      value: 0.256
      name: Cosine Precision@5
    - type: cosine_precision@10
      value: 0.156
      name: Cosine Precision@10
    - type: cosine_recall@1
      value: 0.09566666666666668
      name: Cosine Recall@1
    - type: cosine_recall@3
      value: 0.19366666666666668
      name: Cosine Recall@3
    - type: cosine_recall@5
      value: 0.26466666666666666
      name: Cosine Recall@5
    - type: cosine_recall@10
      value: 0.32266666666666666
      name: Cosine Recall@10
    - type: cosine_ndcg@10
      value: 0.334455012983342
      name: Cosine Ndcg@10
    - type: cosine_mrr@10
      value: 0.556190476190476
      name: Cosine Mrr@10
    - type: cosine_map@100
      value: 0.2621913748440786
      name: Cosine Map@100
  - task:
      type: information-retrieval
      name: Information Retrieval
    dataset:
      name: NanoArguAna
      type: NanoArguAna
    metrics:
    - type: cosine_accuracy@1
      value: 0.14
      name: Cosine Accuracy@1
    - type: cosine_accuracy@3
      value: 0.46
      name: Cosine Accuracy@3
    - type: cosine_accuracy@5
      value: 0.56
      name: Cosine Accuracy@5
    - type: cosine_accuracy@10
      value: 0.74
      name: Cosine Accuracy@10
    - type: cosine_precision@1
      value: 0.14
      name: Cosine Precision@1
    - type: cosine_precision@3
      value: 0.15333333333333332
      name: Cosine Precision@3
    - type: cosine_precision@5
      value: 0.11200000000000002
      name: Cosine Precision@5
    - type: cosine_precision@10
      value: 0.07400000000000001
      name: Cosine Precision@10
    - type: cosine_recall@1
      value: 0.14
      name: Cosine Recall@1
    - type: cosine_recall@3
      value: 0.46
      name: Cosine Recall@3
    - type: cosine_recall@5
      value: 0.56
      name: Cosine Recall@5
    - type: cosine_recall@10
      value: 0.74
      name: Cosine Recall@10
    - type: cosine_ndcg@10
      value: 0.4153702468217312
      name: Cosine Ndcg@10
    - type: cosine_mrr@10
      value: 0.31505555555555553
      name: Cosine Mrr@10
    - type: cosine_map@100
      value: 0.3257288633827949
      name: Cosine Map@100
  - task:
      type: information-retrieval
      name: Information Retrieval
    dataset:
      name: NanoSciFact
      type: NanoSciFact
    metrics:
    - type: cosine_accuracy@1
      value: 0.52
      name: Cosine Accuracy@1
    - type: cosine_accuracy@3
      value: 0.6
      name: Cosine Accuracy@3
    - type: cosine_accuracy@5
      value: 0.64
      name: Cosine Accuracy@5
    - type: cosine_accuracy@10
      value: 0.7
      name: Cosine Accuracy@10
    - type: cosine_precision@1
      value: 0.52
      name: Cosine Precision@1
    - type: cosine_precision@3
      value: 0.21333333333333332
      name: Cosine Precision@3
    - type: cosine_precision@5
      value: 0.136
      name: Cosine Precision@5
    - type: cosine_precision@10
      value: 0.07800000000000001
      name: Cosine Precision@10
    - type: cosine_recall@1
      value: 0.485
      name: Cosine Recall@1
    - type: cosine_recall@3
      value: 0.58
      name: Cosine Recall@3
    - type: cosine_recall@5
      value: 0.62
      name: Cosine Recall@5
    - type: cosine_recall@10
      value: 0.695
      name: Cosine Recall@10
    - type: cosine_ndcg@10
      value: 0.5971766586534905
      name: Cosine Ndcg@10
    - type: cosine_mrr@10
      value: 0.5774126984126984
      name: Cosine Mrr@10
    - type: cosine_map@100
      value: 0.5702830365235684
      name: Cosine Map@100
  - task:
      type: information-retrieval
      name: Information Retrieval
    dataset:
      name: NanoTouche2020
      type: NanoTouche2020
    metrics:
    - type: cosine_accuracy@1
      value: 0.6530612244897959
      name: Cosine Accuracy@1
    - type: cosine_accuracy@3
      value: 0.9387755102040817
      name: Cosine Accuracy@3
    - type: cosine_accuracy@5
      value: 0.9795918367346939
      name: Cosine Accuracy@5
    - type: cosine_accuracy@10
      value: 1.0
      name: Cosine Accuracy@10
    - type: cosine_precision@1
      value: 0.6530612244897959
      name: Cosine Precision@1
    - type: cosine_precision@3
      value: 0.6530612244897959
      name: Cosine Precision@3
    - type: cosine_precision@5
      value: 0.6204081632653061
      name: Cosine Precision@5
    - type: cosine_precision@10
      value: 0.5489795918367346
      name: Cosine Precision@10
    - type: cosine_recall@1
      value: 0.04446978335433603
      name: Cosine Recall@1
    - type: cosine_recall@3
      value: 0.13148110898642992
      name: Cosine Recall@3
    - type: cosine_recall@5
      value: 0.2011530874142959
      name: Cosine Recall@5
    - type: cosine_recall@10
      value: 0.346629494087168
      name: Cosine Recall@10
    - type: cosine_ndcg@10
      value: 0.5978613727330496
      name: Cosine Ndcg@10
    - type: cosine_mrr@10
      value: 0.7910106899902819
      name: Cosine Mrr@10
    - type: cosine_map@100
      value: 0.4526242793573239
      name: Cosine Map@100
  - task:
      type: nano-beir
      name: Nano BEIR
    dataset:
      name: NanoBEIR mean
      type: NanoBEIR_mean
    metrics:
    - type: cosine_accuracy@1
      value: 0.4486970172684458
      name: Cosine Accuracy@1
    - type: cosine_accuracy@3
      value: 0.6583673469387755
      name: Cosine Accuracy@3
    - type: cosine_accuracy@5
      value: 0.7091993720565148
      name: Cosine Accuracy@5
    - type: cosine_accuracy@10
      value: 0.7969230769230768
      name: Cosine Accuracy@10
    - type: cosine_precision@1
      value: 0.4486970172684458
      name: Cosine Precision@1
    - type: cosine_precision@3
      value: 0.30869701726844584
      name: Cosine Precision@3
    - type: cosine_precision@5
      value: 0.24126216640502354
      name: Cosine Precision@5
    - type: cosine_precision@10
      value: 0.1742291993720565
      name: Cosine Precision@10
    - type: cosine_recall@1
      value: 0.24684089910862714
      name: Cosine Recall@1
    - type: cosine_recall@3
      value: 0.4129961637752952
      name: Cosine Recall@3
    - type: cosine_recall@5
      value: 0.47109441153386056
      name: Cosine Recall@5
    - type: cosine_recall@10
      value: 0.5616982441658049
      name: Cosine Recall@10
    - type: cosine_ndcg@10
      value: 0.5110165637352645
      name: Cosine Ndcg@10
    - type: cosine_mrr@10
      value: 0.5645209688270912
      name: Cosine Mrr@10
    - type: cosine_map@100
      value: 0.43117298311998464
      name: Cosine Map@100
---

![SSE](assets/SSE_Logo.png)

If you would like to know more details:

**[SSE Technical Article](https://huggingface.co/blog/RikkaBotan/stable-static-embedding-technical-report)**


![SSE](assets/SSE_comp_en.png)

(a) Retrieval performance (nDCG@10) across NanoBEIR English tasks. (b) Mean nDCG@10 vs. inference speed (QPS: queries per second) measured on TREC-COVID and Quora using an Intel® Core™ Ultra 7 265K (3.90 GHz) with batch size 32.


# 🩵 Quantized SSE: Stable Static Embedding for Retrieval MRL 🩵  
### *A lightweight, faster and powerful embedding model*
  
**Performance Snapshot**  
Our SSE model achieves **NDCG@10 = 0.5110** on NanoBEIR — *slightly outperforming* the popular `static-retrieval-mrl-en-v1` (0.5032) while using **half the dimensions** (512 vs 1024)! 💫 Plus, we're **~2× faster** in retrieval thanks to our compact 512D embeddings and Separable Dynamic Tanh.

This model outperforms related models known for their light weight, while using **15.8x smaller data size**.

The weight data size is **just under 8MB**!

| Model | NanoBEIR NDCG@10 | Dimensions | Parameters | Data size | Speed Advantage | License |
|-------|------------------|------------|------------|-----------------|------------------|---------|
| SSE Retrieval MRL | 0.5124 | 512 | ~16M | 62.5MB | ~2x faster retrieval (ultra-efficient!) | Apache 2.0 |
| **Quantized SSE Retrieval MRL** | **0.5110** ✨ | **512** | **~16M** 🪽 | **7.9MB** 🪽 | **~2x faster retrieval** (ultra-efficient!) | Apache 2.0 |
| `static-retrieval-mrl-en-v1` | 0.5032 | 1024 | ~33M | 125MB | baseline | Apache 2.0 |

[Pre-quantization model](https://huggingface.co/RikkaBotan/stable-static-embedding-fast-retrieval-mrl-en/blob/main/README.md)

---

## 🩵 **Why Choose SSE Retrieval MRL?** 🩵  

✅ **Higher NDCG@10** than all comparable small models (<35M params)  
✅ **Only ~16M parameters** — 27% smaller than MiniLM-L6 (22M) and 52% smaller than BGE-small (33M)  
✅ **512D native output** — richer than 1024D models, yet **half the size** of static-retrieval-mrl-en-v1
✅ **Matryoshka-ready** — smoothly truncate to 256D/128D/64D/32D with graceful degradation  
✅ **Apache 2.0 licensed** — free for commercial & personal use   
✅ **CPU-optimized** — runs faster on edge devices & modest hardware

---

## 🩵 Model Details 🩵

| Property | Value |
|----------|-------|
| **Model Type** | Sentence Transformer (SSE architecture) |
| **Max Sequence Length** | ∞ tokens |
| **Output Dimension** | 512 (with Matryoshka truncation down to 32D!) |
| **Similarity Function** | Cosine Similarity |
| **Language** | English |
| **License** | Apache 2.0 |

```python
SentenceTransformer(
  (0): SSE(
    (embedding): EmbeddingBag(30522, 512, mode='mean')
    (dyt): SeparableDyT()
  )
)
```

![Architecture](assets/SSE_Architecture.png)

---

## 🩵 Mathematical formulations 🩵

Dynamic Tanh Normalization (DyT) enables magnitude-adaptive gradient flow for static embeddings. For input dimension x, DyT computes 
$$ 
y_k = c_k \tanh(a_k x_k + b_k) 
$$ 
with learnable parameters. The gradient of x is:

$$
\frac{\partial y_k}{\partial x_k} = c_k a_k \, \mathrm{sech}^2(a_k x_k + b_k).
$$

For saturated dimensions |x| > 1 
$$
|a_i x_i + b_i| \gg 1 
$$ 
yields exponential decay 
$$ 
\mathrm{sech}^2(z) \sim 4e^{-2|z|} 
$$
suppressing gradients as 
$$ 
\partial y_i / \partial x_i \to 0 
$$
For non-saturated dimensions |x| << 1 , 
$$ 
\mathrm{sech}^2(z) \approx 1 
$$ 
preserves near-constant gradients 
$$ 
\partial y_j / \partial x_j \approx c_j a_j 
$$
This magnitude-dependent gating attenuates learning signals from noisy, large-magnitude dimensions while maintaining full gradient flow for stable, informative dimensions—providing implicit regularization that enhances generalization without explicit hyperparameters.

---

## 🩵 Evaluation Results (NanoBEIR) 🩵

| Dataset | NDCG@10 | MRR@10 | MAP@100 |
|---------|--------|-------|--------|
| **NanoBEIR Mean** | **0.5110** | **0.5645** | **0.4312** |
| NanoClimateFEVER | 0.3127 | 0.3822 | 0.2439 |
| NanoDBPedia | 0.5472 | 0.7440 | 0.4252 |
| NanoFEVER | 0.6870 | 0.6402 | 0.6191 |
| NanoFiQA2018 | 0.3750 | 0.4155 | 0.3129 |
| NanoHotpotQA | 0.6927 | 0.7572 | 0.6205 |
| NanoMSMARCO | 0.4105 | 0.3504 | 0.3694 |
| NanoNFCorpus | 0.3063 | 0.4989 | 0.1148 |
| NanoNQ | 0.4523 | 0.3884 | 0.3941 |
| NanoQuoraRetrieval | **0.9147** | **0.9222** | **0.8944** |
| NanoSCIDOCS | 0.3345 | 0.5562 | 0.2622 |
| NanoArguAna | 0.4154 | 0.3151 | 0.3257 |
| NanoSciFact | 0.5972 | 0.5774 | 0.5703 |
| NanoTouche2020 | 0.5979 | 0.7910 | 0.4526 |

---

## 🩵 How to use? 🩵

```python
import torch
from sentence_transformers import SentenceTransformer

# load (remote code enabled)
model = SentenceTransformer(
    "RikkaBotan/quantized-stable-static-embedding-fast-retrieval-mrl-en",
    trust_remote_code=True,
    device="cuda" if torch.cuda.is_available() else "cpu",
)

# inference
sentences = [
    "Stable Static embedding is interesting.",
    "SSE works without attention."
]

with torch.no_grad():
    embeddings = model.encode(
        sentences,
        convert_to_tensor=True,
        normalize_embeddings=True,
        batch_size=32
    )

# cosine similarity
# cosine_sim = embeddings[0] @ embeddings[1].T
cosine_sim = model.similarity(embeddings, embeddings)

print("embeddings shape:", embeddings.shape)
print("cosine similarity matrix:")
print(cosine_sim)
```
---
## 🩵 Retrieval usage 🩵

```python
import torch
from sentence_transformers import SentenceTransformer

# load (remote code enabled)
model = SentenceTransformer(
    "RikkaBotan/quantized-stable-static-embedding-fast-retrieval-mrl-en",
    trust_remote_code=True,
    device="cuda" if torch.cuda.is_available() else "cpu",
)

# inference
query = "What is Stable Static Embedding?"
sentences = [
    "SSE: Stable Static embedding works without attention.",
    "Stable Static Embedding is a fast embedding method designed for retrieval tasks.",
    "Static embeddings are often compared with transformer-based sentence encoders.",
    "I cooked pasta last night while listening to jazz music.",
    "Large language models are commonly trained using next-token prediction objectives.",
    "Instruction tuning improves the ability of LLMs to follow human-written prompts.",
]


with torch.no_grad():
    embeddings = model.encode(
        [query] + sentences,
        convert_to_tensor=True,
        normalize_embeddings=True,
        batch_size=32
    )

print("embeddings shape:", embeddings.shape)

# cosine similarity
similarities = model.similarity(embeddings[0], embeddings[1:])
for i, similarity in enumerate(similarities[0].tolist()):
    print(f"{similarity:.05f}: {sentences[i]}")
```

---

## 🩵 Training Hyperparameters 🩵

#### Non-Default Hyperparameters

- `eval_strategy`: steps
- `per_device_train_batch_size`: 512
- `gradient_accumulation_steps`: 8
- `learning_rate`: 0.1
- `adam_beta2`: 0.9999
- `adam_epsilon`: 1e-10
- `num_train_epochs`: 1
- `lr_scheduler_type`: cosine
- `warmup_ratio`: 0.1
- `bf16`: True
- `dataloader_num_workers`: 4
- `batch_sampler`: no_duplicates

---

## 🩵 Training Datasets 🩵

We learned from **14 datasets**:

| Dataset |
|---------|
| `squad` |
| `trivia_qa` |
| `allnli` |
| `pubmedqa` |
| `hotpotqa` |
| `miracl` |
| `mr_tydi` |
| `msmarco` |
| `msmarco_10m` |
| `msmarco_hard` |
| `mldr` |
| `s2orc` |
| `swim_ir` |
| `paq` |
| `nq` |
| `scidocs` |

*All trained with **MatryoshkaLoss** — learning representations at multiple scales like Russian nesting dolls!*

## 🩵 Training results 🩵

![loss](assets/SSE_loss.png)

![ndcg](assets/SSE_ndcg.png)

## 🩵 About me 🩵

Japanese independent researcher having shy and pampered personality. Twin-tail hair is a charm point. Interested in nlp. Usually using python and C.

X(Twitter):
https://twitter.com/peony__snow

![Logo](assets/RikkaBotan_Logo.png)

## 🩵 Acknowledgements 🩵

The author acknowledge the support of Saldra, Witness and Lumina Logic Minds for providing computational resources used in this work.

I thank the developers of sentence-transformers, python and pytorch.

I thank all the researchers for their efforts to date.

I thank Japan's high standard of education.

And most of all, thank you for your interest in this repository.

## 🩵 Citation 🩵

### BibTeX

#### Sentence Transformers
```bibtex
@inproceedings{reimers-2019-sentence-bert,
    title = "Sentence-BERT: Sentence Embeddings using Siamese BERT-Networks",
    author = "Reimers, Nils and Gurevych, Iryna",
    booktitle = "Proceedings of the 2019 Conference on Empirical Methods in Natural Language Processing",
    month = "11",
    year = "2019",
    publisher = "Association for Computational Linguistics",
    url = "https://arxiv.org/abs/1908.10084",
}
```

#### MatryoshkaLoss
```bibtex
@misc{kusupati2024matryoshka,
    title={Matryoshka Representation Learning},
    author={Aditya Kusupati and Gantavya Bhatt and Aniket Rege and Matthew Wallingford and Aditya Sinha and Vivek Ramanujan and William Howard-Snyder and Kaifeng Chen and Sham Kakade and Prateek Jain and Ali Farhadi},
    year={2024},
    eprint={2205.13147},
    archivePrefix={arXiv},
    primaryClass={cs.LG}
}
```

#### MultipleNegativesRankingLoss
```bibtex
@misc{henderson2017efficient,
    title={Efficient Natural Language Response Suggestion for Smart Reply},
    author={Matthew Henderson and Rami Al-Rfou and Brian Strope and Yun-hsuan Sung and Laszlo Lukacs and Ruiqi Guo and Sanjiv Kumar and Balint Miklos and Ray Kurzweil},
    year={2017},
    eprint={1705.00652},
    archivePrefix={arXiv},
    primaryClass={cs.CL}
}
```