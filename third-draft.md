# AIST: Manifesting Implicit Dependencies in Requirements Changes – An Automated Method Integrating Code Awareness and Change Analysis

## Abstract

In modern software development, accurately assessing the scope of impact of requirement changes is crucial for ensuring software quality, yet this task faces two major challenges. First, highly simplified requirement descriptions ("one-sentence requirements") tend to conceal complex business logic and extensive system dependencies. Second, commits often intertwine code changes for multiple requirements, leading to impact assessments that are both incomplete and time-consuming. Traditional manual assessment methods are not only inefficient but also prone to cognitive biases, which often result in the omission of critical impact points.

To address these problems, this paper proposes AIST (Analysis of Impact Scope of Task), an automated impact scope analysis approach based on Large Language Models (LLMs). AIST leverages the semantic understanding and planning capabilities of LLMs to automatically parse requirement descriptions, retrieve relevant code context from the codebase, and integrate code change information, thereby generating a comprehensive impact scope assessment report.
We evaluated AIST on 40 historical requirement changes from three real-world industrial projects. The experimental results show that it achieves a precision of 75.18%, a recall of 94.30%, and an F1-score of 82.97%, thereby significantly improving the completeness of impact scope identification and demonstrating high sensitivity in detecting tangled code changes. In a user survey, the novelty score was 6.32 out of 7.00, and the overall satisfaction score was 6.36 out of 7.00, indicating that users perceive that the tool effectively improves efficiency and reduces the risk of omissions.

**Keywords:** impact analysis; requirement expansion; change code analysis; code retrieval; large language model; software engineering

---

## 1. Introduction

### 1.1 Research Background

Requirement changes serve both as a driver of feature enhancement and as a major source of development risk [1–3]. When requirements are modified, developers typically respond by implementing corresponding code changes [4]. However, the impact of these code modifications often extends far beyond the immediate changes, propagating through dependency chains and potentially destabilizing seemingly unrelated system functionalities [5–7].

In agile iterative projects, product requirements are often conveyed in a condensed format — ranging from brief verbal instructions to short descriptions in issue tracking systems, colloquially referred to by technical teams as “one-sentence requirements” [8–10]. This approach frequently creates interpretive gaps among project team members. Although manual assessment attempts to compensate for the cognitive limitations of individuals in different roles, it remains inefficient. In practice, this manual approach repeatedly reveals that certain impact points are overlooked, leading to gaps in regression test coverage and defect escapes [11]. Meanwhile, relying on individual intuition to assess impact scope is susceptible to the anchoring effect, posing significant cognitive risks. Additionally, psychological mechanisms such as the planning fallacy and overconfidence predispose individuals to envision the smoothest execution paths, thereby overlooking edge cases and hidden risks [12–14].

To address the above problems, this paper proposes AIST to achieve: (1) enriching high-level, outline "one-sentence requirements" into more comprehensive change descriptions; (2) providing explainable descriptions of the system behavior changes caused by code modifications, thereby making the actual impact of development changes transparent [15]. For testers, this further illuminates "what to test" and "why to test," ultimately enabling them to adopt more effective and requirement-aligned testing strategies [16–17].

### 1.2 Research Questions

To systematically evaluate the effectiveness of the AIST method, this study formulates the following three research questions:

- **RQ1 (Effectiveness Evaluation):** **Compared to manual assessment, what is the percentage improvement achieved by AIST in precision, recall, and F1-score?**
- **RQ2 (Codebase Retrieval Effectiveness):** **Comparing the configuration that uses only the raw requirement and base model against the configuration that adds codebase retrieval (where the retrieval is directly driven by the requirement description), what is the improvement effect on precision, recall, and F1-score?**
- **RQ3 (Component Synergy Effect):** Comparing "requirement + codebase retrieval", "code change analysis" respectively with AIST (which integrates requirements, codebase, and code changes), what is the quantitative assessment of each component's contribution?

## 2. Related Work

In the domain of requirements engineering, although visual modeling and AI-assisted parsing tools can reduce testing workload and shorten delivery cycles, they often require substantial adjustments to development processes or are limited to requirements management and test case generation [18–20]. In the field of code change-based impact analysis, static dependency analysis, version history mining, and machine learning methods can effectively identify affected modules or test cases, but because they are detached from the original requirement context, the analysis results fail to capture the semantic associations of requirement intent [21–23]. In requirement-code traceability research, although automated traceability link generation and large language model-based tracing have established connections between requirements and code to some extent, most of them take static traceability links as the final output and do not integrate requirement changes with code changes into a unified impact assessment framework [24–26]. Existing studies have attempted to combine requirements, test cases, and code, but they rely heavily on manual effort and lack dynamic flexibility [27]. Machine learning-based impact analysis approaches like UTANGO, although employing graph convolutional networks to learn contextual embeddings of code changes, struggle to incorporate requirement context due to the absence of requirement semantics and business background [28–30]. The AIST method proposed in this paper addresses the aforementioned gaps by leveraging the planning and coordination capabilities of large language models to organically integrate requirement semantics, codebase context, and code change information, achieving a lightweight, dynamic, and highly interpretable change impact scope analysis.

## 3. Methodology

### 3.1 Method Overview

The AIST method employs a Large Language Model as the "impact analysis decision-maker" to automate the assessment of requirement impact scope. The overall process is illustrated in Figure 1, where the core lies in combining the reasoning capabilities of the LLM with code analysis tools to perform a comprehensive analysis from requirement description to impact scope.
Figure 1 AIST method overview
### 3.2 Technical Implementation Details

AIST employs the DeepSeek-Chat model (temperature=0.0) as the core reasoning engine. Its implementation encompasses the following key aspects:

1. **Requirement Semantic Understanding and Planning:** The method first automatically parses the requirement description, extracting the key business entities and operations involved. On this basis, it employs a task decomposition strategy to guide the LLM in generating intermediate reasoning steps and enhances the model's reasoning capability through prompt templates.

2. **Multi-tool Collaborative Invocation:** To achieve precise code impact analysis, AIST integrates three types of tools:
   a) **Code Query Tool:** Extracts entity terms (e.g., "order", "financial ledger") from the requirement description, performs fuzzy matching against class names, method names, and comments in the project, ranks and retrieves relevant files by matching frequency, and intercepts matched lines along with their context as the basis for analysis.
   b) **Git Tool:** Obtains code change diff information based on commit IDs, parses the modified class names, method names, and specific changed lines, and then combines them with the requirement description to semantically interpret the business intent of the changes.
   c) **Call Chain Parsing Tool:** Parses the relevant code and change source code using AST based on Eclipse JDT, identifying method declarations, method calls, and class inheritance relationships. Using the changed methods or requirement-related entry methods as seed nodes, it performs forward call analysis (downstream dependencies) and backward call analysis (upstream dependencies) to construct a complete impact propagation path.

3. **Impact Scope Synthesis:** Integrates requirement text, codebase context, and change information to generate the final list of impacted points.

---

## 4. Experimental Design

### 4.1 Experimental Subjects

This experiment was conducted on three real-world software projects actively developed for over four years: **the Customer Management System, the Order Management System, and the Financial System**. A total of 40 historical requirement changes that had been implemented were collected from the project change management systems, each comprising a natural language requirement description and its corresponding code changes.

### 4.2 Baseline Methods

**Manual Evaluation Baseline:** **Two experienced software developers who also serve as testers** from each project independently assessed the impact scope based on the provided requirement descriptions.

**AIST Configuration Variants:** To evaluate the contribution of each component, we established the following configurations:

- **A1 (Raw Requirement):** The AI model is provided only with the raw requirement description and is asked to assess the impact scope.
- **A2 (Retrieval-Augmented):** The AI model is first provided with the raw requirement, which is then used as a query to retrieve relevant code snippets from the codebase; the model assesses the impact scope based on the requirement and the retrieved code context.
- **A3 (Code Change Analysis Only):** Impact analysis based solely on code changes.
- **A4 (Full AIST):** The AI model is provided concurrently with the code changes, the raw requirement document, and the relevant context retrieved from the codebase, synthesizing all information to generate the final set of impacted points.

### 4.3 Evaluation Metrics

To quantitatively compare the performance of different methods, we employed three standard information retrieval metrics: precision, recall, and F1-score [31–32]:


Precision = |Valid Impact Points Identified| / |Total Impact Points Identified|
Recall = |Correctly Identified Impact Points by the Method| / |Total Actual Impact Points|
F1-score = 2 × (Precision × Recall) / (Precision + Recall)


### 4.4 Ground Truth Construction

To obtain a reliable and reproducible ground truth, this paper invited two senior testing experts not involved in the design of this method to perform the final determination following a causal logic of "candidate generation → evidence supplementation → expert adjudication," synthesizing three types of objective evidence (code changes, dependency relationships, existing test cases). First, based on code changes, bidirectional dependency propagation analysis is performed to capture all potentially affected code elements (classes, methods, interfaces, routes, etc.), generating "candidate impact points." Simultaneously, existing test cases associated with the requirement are retrieved from the DevOps platform, extracting the implicitly validated functionalities as "validated impact point" candidates. Subsequently, the two experts independently evaluate each candidate point, with the determination criterion being that the point must be supported by at least one piece of objective evidence from either the changed code or existing test cases. If their opinions are consistent, the point is adopted; otherwise, consensus is reached through discussion. Ultimately, the Ground Truth is defined as the set of impact points unanimously recognized by the two experts. This process ensures that the GT generation has clear input-process-output causality, with each step relying on traceable objective evidence, thereby enhancing the credibility and reproducibility of the ground truth.

### 4.5 Experimental Procedure

To validate the effectiveness of the AIST method, this study designed three sets of experiments, with all evaluation metrics being precision, recall, and F1-score.

**Primary Comparison Experiment (RQ1):** For each requirement in the test set, using the manually annotated set of test points as the benchmark, the A1, A2, A3, and A4 methods are respectively employed to generate predicted test point sets, and **the precision, recall, and F1-score** of each method are calculated.

**Ablation Experiment (RQ2 & RQ3):** RQ2 compares A1 (Raw Requirement) with A2 (Retrieval-Augmented Requirement) to quantify the contribution of codebase retrieval; RQ3 compares A2, A3 (Change Analysis Only) with A4 (AIST) to examine the synergistic effects of the components.

**Statistical Significance Analysis:** The Cliff's Delta and Wilcoxon methods are employed to conduct significance analysis on the A1-A2 comparison and the A2-A3-A4 comparison, respectively [33–34], where the effect size of Cliff's Delta is determined by |δ|: <0.147 as negligible, 0.147–0.33 as small, 0.33–0.474 as medium, and ≥0.474 as large.

---

## 5. Experimental Results and Analysis

### 5.1 Comprehensive Performance Comparison

To systematically evaluate the impact analysis capabilities of the AIST method and its variants, Table 1 summarizes the performance of all configurations (Manual Evaluation M, Raw Requirement A1, Retrieval-Augmented Requirement A2, Code Change Analysis Only A3, and Full AIST Method A4) in terms of precision, recall, and F1-score.

**Table 1: Comprehensive Performance Comparison of Different Configurations**

| Configuration | Precision (%) | Recall (%) | F1-score (%) |
|---------------|---------------|------------|---------------|
| Manual Evaluation (M) | 89.90 | 26.77 | 38.30 |
| A1 (Raw Requirement) | 66.65 | 30.49 | 40.55 |
| A2 (Retrieval-Augmented Requirement) | 74.19 | 49.30 | 58.21 |
| A3 (Code Change Analysis Only) | 70.33 | 45.23 | 54.03 |
| A4 (Full AIST) | 75.18 | 94.30 | 82.97 |

Based on Table 1, the following three subsections discuss the gain mechanisms and component synergy effects of the AIST method.

---

### 5.2 Comparison between AIST Method and Manual Evaluation (RQ1)

Based on Table 1, compared to manual evaluation, the AIST method achieves an improvement of 67.53 percentage points in recall and 44.67 percentage points in F1-score. Although the precision of A4 is lower than that of manual evaluation, the impact points identified by AIST are, upon manual verification, mostly confirmed as valid potential risk points that are easily overlooked. This result indicates that the AIST method can mitigate the "anchoring effect" of manual assessment, providing a more complete and reliable impact scope evaluation.

---

### 5.3 Impact of Codebase Retrieval (RQ2)

Based on Table 1, after introducing codebase retrieval, precision improves by 7.54 percentage points, recall improves by 18.81 percentage points, and F1-score improves by 17.66 percentage points. It is evident that relying solely on the knowledge of the LLM cannot adequately address project complexity. Matching raw requirements against codebase context enhances the LLM's perception of project-specific logic, enabling it to make more accurate and complete impact predictions.

---

### 5.4 Component Synergy Effect (RQ3)

Based on Table 1, the AIST method integrates the cross-constraint mechanism of requirement semantics and code change information, achieving an effect greater than that of any single component: Compared to A2, AIST improves recall by 45.00 percentage points, F1-score by 24.76 percentage points, and precision by a slight 0.99 percentage points. Compared to A3, AIST improves recall by 49.07 percentage points, F1-score by 28.94 percentage points, and precision by 4.85 percentage points.

### 5.5 Statistical Significance Analysis

Figure 2 presents the Cliff's Delta effect sizes and the comparative results under the Wilcoxon signed-rank test for different configuration comparisons.

**Key Findings:**
From Figure 2, in the comparison between A1 and A2, the δ values for recall and F1-score reach 0.8750 and 0.9000, respectively, both indicating a large effect. The δ value for precision is 0.2500, indicating a small effect. This suggests that codebase retrieval contributes to the completeness of impact scope identification, while its effect on precision is relatively moderate.

In the comparisons between AIST and A2, as well as between AIST and A3, the δ values for precision are -0.0250 and 0, respectively, indicating a negligible effect. This indicates that AIST performs on par with A2 or A3 in terms of precision, i.e., the AIST method does not introduce an additional false-positive burden. The δ values for both recall and F1-score are 1, indicating a large effect. The results demonstrate that integrating requirement semantics, codebase context, and code changes yields a statistically significant improvement in recall and F1-score.

### 5.6 Cross-Project Stability Analysis

**Table 2: Performance of AIST across Different Systems**

| System | Precision (%) | Recall (%) | F1-score (%) |
|---------|---------------|------------|---------------|
| Order Management System | 74.78 | 95.91 | 83.43 |
| Customer Management System | 73.78 | 90.07 | 80.33 |
| Financial System | 77.36 | 95.31 | 84.69 |
| Range (Max – Min) | 3.59 | 5.84 | 4.36 |

Figure 3

**Key Findings:**

As shown in Table 2, the AIST method performs stably across the three business-domain Java+Vue projects: precision ranges from 73.78% to 77.36%, with a range of 3.59 percentage points, indicating that the method maintains good consistency across different business logics and code structures. Recall ranges from 90.07% to 95.91%, with a range of 5.84 percentage points. F1-score ranges from 80.33% to 84.69%, with a range of 4.36 percentage points, further validating the performance stability of AIST across different projects. In summary, this demonstrates that the AIST method possesses good generalizability and robustness across Java+Vue projects in different business domains, and its performance does not depend on specific domain knowledge or codebase characteristics, indicating its potential for cross-project transferability.

### 5.7 User Survey Results

We conducted a questionnaire survey with 22 software engineers who used AIST, employing a 7-point Likert scale. A total of 16 questions were designed, covering six dimensions. Table 3 presents the survey results.

**Table 3: User Survey Scoring Results**

| Evaluation Dimension | Mean | SD |
|----------------------|------|----|
| Accuracy | 6.0000 | 0.9759 |
| Novelty of impact point identification | 6.3182 | 0.6463 |
| False positives of irrelevant content (reverse) | 2.5455 | 1.0568 |
| Process intuitiveness | 6.2727 | 0.7025 |
| Learning cost | 6.2727 | 0.6311 |
| Trust reference degree | 5.9545 | 1.0455 |
| Need for manual review (reverse score) | 6.0909 | 0.6838 |
| Understanding business impact | 6.2727 | 0.6311 |
| Irrelevant information (reverse) | 5.9545 | 0.6530 |
| Report organization | 6.0909 | 0.6102 |
| Backend focus | 5.2727 | 0.0725 |
| Weak third-party library handling | 4.0909 | 0.8112 |
| Efficiency improvement | 6.3182 | 0.6463 |
| Overall satisfaction | 6.3636 | 0.6580 |
| Willingness to continue using | **6.3636** | 0.6580 |
| Willingness to recommend | 6.1818 | 0.6645 |

**Key Findings:**

1. **High Satisfaction and Willingness to Use:** Overall satisfaction (M=6.36) and willingness to continue using **(M=6.36)** are both at high levels, reflecting users' high satisfaction with AIST and their intention to continue using it.
2. **Outstanding Novelty:** Novelty of impact point identification (M=6.32) and efficiency improvement (M=6.32) are high, demonstrating that AIST can systematically uncover potential risk points easily overlooked in manual assessment and shorten analysis time, thereby improving efficiency.
3. **Excellent Output Quality:** Accuracy reaches (M=6.00), while false positive rate (M=2.55) and irrelevant information (M=5.95) (both reverse-scored) indicate that the reports generated by AIST have high information density, containing a substantial amount of truly noteworthy change impacts.
4. **Areas for Improvement:** The handling of third-party libraries received a relatively low score (M=4.09), suggesting that the tool may have deficiencies in analyzing changes involving external library dependencies. Future versions could focus on optimizing this aspect.

---

## 6. Typical Case Analysis

This section uses an industrial case to illustrate the gain mechanisms underlying the statistical results presented in Section 5.

#### 6.1 Industrial Case: Raw Requirement and Implementation of Including Distribution Orders in Performance Statistics

The work order's raw description is "Performance statistics include distribution orders." This description entails three implicit constraints requiring clarification at the implementation level: (1) "Performance statistics" in the existing system corresponds to three different statistical scopes: SKU reports, department reports, and salesperson reports; (2) "Distribution orders" involve three external subsystems: distribution, warehousing, and customer management; (3) Among the six Git commits corresponding to this requirement, some commits are also associated with another work order, "Automation of collection order generation," constituting cross-requirement coupling that is not declared in the work order.

#### 6.2 Cross-Constraint of Code Awareness and Change Information

Manual assessment yielded 4 test points, all centered around the shipment status of distribution orders. A1 output 18 test points, primarily based on literal semantic expansion, failing to access project-specific interfaces and call relationships, and thus unable to identify external dependencies such as the distribution center. A2, by retrieving the call relationships of `FeignCommonDao`, supplemented the external system integration dimension but, lacking access to code changes, could not distinguish between existing logic and the current implementation. A3 identified that the commits modified both the performance statistics interface and the collection order scheduled task, but due to the lack of business context, it could not determine the association between the two. The AIST method, through the cross-validation of the three information sources, discovered that commits `15df5696`, `cad9254e`, etc., modified functions related to collection orders, such as `findOrderDates` and `manualGeneration`. Since the work order description was "Performance statistics include distribution orders," it inferred that collection order-related order statistics should similarly include distribution order data, analogous to the performance statistics method. Consequently, it included the impact points related to collection orders within the test scope of this work order. This achieved a precision of 0.939 and a recall of 0.954, significantly outperforming manual and other methods. This case reveals the core gain mechanism of the AIST method in requirement impact scope analysis: by applying cross-constraints to requirement semantics, codebase context, and code change information, AIST can mitigate the "anchoring effect" of manual assessment and the semantic blind spots of single information sources.

## 7. Discussion and Conclusion

The experimental results consistently demonstrate that the AIST method significantly improves the accuracy and completeness of requirement change impact analysis on the industrial dataset. The ablation study confirms that requirement-guided code retrieval, code change analysis, codebase context, and their combination all positively contribute to overall performance. Introducing codebase retrieval enhancement improves precision by 7.54 percentage points, recall by 18.81 percentage points, and F1-score by 17.66 percentage points, validating the value of project-specific codebase context in enhancing LLM-based impact analysis capability. More importantly, the fusion of requirement semantics and code change information produces a significant synergistic effect—compared to retrieval-augmented requirements alone and code change analysis alone, AIST improves recall by 45.00 and 49.07 percentage points, and F1-score by 24.76 and 28.94 percentage points, respectively. The results indicate that the requirement description provides the business intent of the change ("why it changed"), while the code change reveals the implementation details ("how it changed"), and the cross-validation and bidirectional constraint between the two achieve a "1+1>2" gain. In terms of practical value, AIST can increase the recall of requirement impact points from approximately 27% in manual assessment to over 94%, and it exhibits high sensitivity to **tangled commits**, effectively uncovering implicit requirements mixed into commits. Additionally, the learning cost score in the user survey is only 6.27/7.00, indicating that the method can be adopted by project members without additional training.

Nevertheless, AIST has several limitations: it is highly dependent on the quality and accuracy of codebase retrieval, with a significant decline in recall for code entities that are poorly named or mixed-language; the context window limitations of the LLM may lead to the loss of critical dependency information; there is still room for improvement in precision, and the non-deterministic output of the LLM may cause fluctuations in results across multiple runs on the same sample; the current experimental Ground Truth may be constrained by the coverage of expert knowledge and the completeness of historical test cases. Furthermore, the dataset selected for this study includes only three Java+Vue projects. Although they cover different business domains, the generalizability of the results to other programming languages or project types requires further validation. Future work will explore code retrieval methods to enhance cross-language robustness, investigate LLM memorization techniques to address context limitations, design multi-round self-consistency mechanisms to improve output stability, and integrate static analysis and user profiling mechanisms to automatically validate the impact points output by the LLM, thereby reducing false-positive rates. Additionally, we will extend AIST to fine-grained, code-change-line-level analysis to achieve true "single-code-commit tangling detection" and conduct large-scale deployment validation on more industrial projects.

---

## References

[1] H. Hakim, A. Sellami, H. Ben-Abdallah, "A multi-level measures-driven change impact analysis approach for prioritizing software requirement changes," *Innovations in Systems and Software Engineering*, vol. 21, no. 4, pp. 1445–1478, 2025.

[2] S. McGee, D. Greer, "Sources of software requirements change from the perspectives of development and maintenance," *International Journal on Advances in Software*, vol. 3, no. 1-2, pp. 186–200, 2010.

[3] S. E. McGee, "Software requirements change analysis and prediction," Ph.D. dissertation, Queen's University Belfast, 2014.

[4] D. Jayasuriya, S. Ou, S. Hegde, V. Terragni, J. Dietrich, K. Blincoe, "An extended study of syntactic breaking changes in the wild," *Empirical Software Engineering*, vol. 30, no. 2, p. 42, 2025.

[5] D. Li, L. Li, D. Kim, T. F. Bissyandé, D. Lo, Y. Le Traon, "Watch out for this commit! a study of influential software changes," *Journal of Software: Evolution and Process*, vol. 31, no. 12, p. e2181, 2019.

[6] E.-M. Arvanitou, A. Ampatzoglou, A. Chatzigeorgiou, P. Avgeriou, N. Tsiridis, "A metric for quantifying the ripple effects among requirements," *Software Quality Journal*, vol. 30, no. 3, pp. 853–883, 2022.

[7] R. J. Turver, M. Munro, "An early impact analysis technique for software maintenance," *Journal of Software Maintenance: Research and Practice*, vol. 6, no. 1, pp. 35–52, 1994.

[8] K. Tsilionis, A. R. Amna, S. Heng, S. Poelmans, G. Poels, "Controlled experiments on user stories' modeling: past, present, and future," in *CEUR Workshop Proceedings*, vol. 3134, 2022.

[9] F. A. M. Domínguez, M. A. Quintana, G. Borrego, S. Gonz et al., "Role of Acceptance Criteria and Developer Expertise in Enhancing the Quality of Robustness Diagrams in Agile Software Development," *IEEE Latin America Transactions*, vol. 23, no. 12, pp. 1211–1218, 2025.

[10] S. Molenaar, F. Dalpiaz, "The impact of requirements artifacts on efficiency in agile development: a case study," in *2025 IEEE 33rd International Requirements Engineering Conference (RE)*, pp. 68–79, 2025.

[11] L. Gren, R. Berntsson Svensson, "Is it possible to disregard obsolete requirements? a family of experiments in software effort estimation," *Requirements Engineering*, vol. 26, no. 3, pp. 459–480, 2021.

[12] J. A. O. G. da Cunha, J. J. L. Dias Jr, L. M. R. de Vasconcelos Cunha, H. Moura, "Software Processes Improvement in light of Cognitive Biases: A Cross-Case Analysis," 2015.

[13] P. Ralph, "Toward a theory of debiasing software development," in *Eurosymposium on Systems Analysis and Design*, pp. 92–105, Springer, 2011.

[14] O. Shmueli, N. Pliskin, L. Fink, "Can the outside-view approach improve planning decisions in software development projects?" *Information Systems Journal*, vol. 26, no. 4, pp. 395–418, 2016.

[15] F. Khalil, G. Rebdawi, N. Ghneim, "A Systematic Mapping Review: Tracking the Relationships Between Software Artifacts using NLP," *ECTI Transactions on Computer and Information Technology (ECTI-CIT)*, vol. 19, no. 2, pp. 321–333, 2025.

[16] A. Orso, T. Apiwattanapong, M. J. Harrold, "Leveraging field data for impact analysis and regression testing," *ACM SIGSOFT Software Engineering Notes*, vol. 28, no. 5, pp. 128–137, 2003.

[17] D. Wang, Y. Zhao, L. Xiao, T. Yu, "An empirical study of regression testing for android apps in continuous integration environment," in *2023 ACM/IEEE International Symposium on Empirical Software Engineering and Measurement (ESEM)*, pp. 1–11, 2023.

[18] B. Wang, R. Peng, Y. Li, H. Lai, Z. Wang, "Requirements traceability technologies and technology transfer decision support: A systematic review," *Journal of Systems and Software*, vol. 146, pp. 59–79, 2018.

[19] E. Alégroth, K. Karl, H. Rosshagen, T. Helmfridsson, N. Olsson, "Practitioners' best practices to adopt, use or abandon model-based testing with graphical models for software-intensive systems," *Empirical Software Engineering*, vol. 27, no. 5, p. 103, 2022.

[20] S.-C. Necula, F. Dumitriu, V. Greavu-Șerban, "A systematic literature review on using natural language processing in software requirements engineering," *Electronics*, vol. 13, no. 11, p. 2055, 2024.

[21] T. O. Aro, Y. Shakirat, A. Bajeh, K. Adewole, "Improving the accuracy of static source code based software change impact analysis through hybrid techniques: A review," *International Journal of Software Engineering and Computer Systems*, vol. 7, no. 1, pp. 57–66, 2021.

[22] T. Zimmermann, A. Zeller, P. Weissgerber, S. Diehl, "Mining version histories to guide software changes," *IEEE Transactions on Software Engineering*, vol. 31, no. 6, pp. 429–445, 2005.

[23] T. H. M. Le, H. Chen, M. A. Babar, "Deep learning for source code modeling and generation: Models, applications, and challenges," *ACM Computing Surveys (CSUR)*, vol. 53, no. 3, pp. 1–38, 2020.

[24] M. Borg, P. Runeson, A. Ardö, "Recovering from a decade: a systematic mapping of information retrieval approaches to software traceability," *Empirical Software Engineering*, vol. 19, no. 6, pp. 1565–1616, 2014.

[25] J. Cleland-Huang, B. Berenbach, S. Clark, R. Settimi, E. Romanova, "Best practices for automated traceability," *Computer*, vol. 40, no. 6, pp. 27–35, 2007.

[26] A. Vogelsang, A. Korn, G. Broccia, A. Ferrari, J. Fischbach, C. Arora, "On the impact of requirements smells in prompts: The case of automated traceability," in *2025 IEEE/ACM 47th International Conference on Software Engineering: New Ideas and Emerging Results (ICSE-NIER)*, pp. 51–55, 2025.

[27] S. Ibrahim, N. B. Idris, M. Munro, A. Deraman, "Integrating Software Traceability for Change Impact Analysis," *Int. Arab J. Inf. Technol.*, vol. 2, no. 4, pp. 301–308, 2005.

[28] R. Tufano, "Automating code review," in *2023 IEEE/ACM 45th International Conference on Software Engineering: Companion Proceedings (ICSE-Companion)*, pp. 192–196, 2023.

[29] N. Jiang, K. Liu, T. Lutellier, L. Tan, "Impact of code language models on automated program repair," in *2023 IEEE/ACM 45th International Conference on Software Engineering (ICSE)*, pp. 1430–1442, 2023.

[30] B. Pathik, M. Sharma, "Source code change analysis with deep learning based programming model," *Automated Software Engineering*, vol. 29, no. 1, p. 15, 2022.

[31] C. D. Manning, P. Raghavan, H. Schütze, *Introduction to Information Retrieval*. Cambridge University Press, 2008.

[32] M. Sokolova, G. Lapalme, "A systematic analysis of performance measures for classification tasks," *Information Processing & Management*, vol. 45, no. 4, pp. 427–437, 2009.

[33] N. Cliff, "Dominance statistics: Ordinal analyses to answer ordinal questions," *Psychological Bulletin*, vol. 114, no. 3, p. 494, 1993.

[34] K. Meissel, E. S. Yao, "Using Cliff's delta as a non-parametric effect size measure: an accessible web app and R tutorial," *Practical Assessment, Research, and Evaluation*, vol. 29, no. 1, 2024.

---

**Appendix A: Prompt Engineering Template Examples** (omitted)

**Appendix B: Dataset Details** (omitted)