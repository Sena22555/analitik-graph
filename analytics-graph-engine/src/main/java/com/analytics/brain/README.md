# Папка brain — карта классов

Вид класса написан в начале имени: `Interface…` — интерфейс, `Abstract…` — абстрактный класс, `Final…` — final-класс, `Enum…` — перечисление, `Exception…` — исключение, `Service…` — Spring-сервис, `Controller…` — REST-контроллер.

Подробное объяснение каждого класса — в файле `Analitik_Graph_klassy_brain.pdf` в корне проекта.

## api — REST-контроллеры

- `AbstractApiController`
- `ControllerAiApi` — extends AbstractApiController
- `ControllerBrainApi` — extends AbstractApiController
- `FinalCreateDatasetRequest`

## service — построение графа

- `FinalAssociationLinks`
- `FinalBranchBuilder`
- `FinalGraphPaths`
- `FinalInsightFinder`
- `ServiceBrainGraph`

## ai — ИИ-аналитик

- `FinalChatRequest`
- `InterfaceAiTool`
- `ServiceAiChat`
- `ServiceAiClient`
- `ServiceAiDocuments`
- `ServiceAiTools`

## analysis — анализы

- `AbstractAnalitikAnalysis`
- `AbstractAnomalyAnalysis` — extends AbstractAnalitikAnalysis
- `FinalAbcAnalysis` — extends AbstractAnalitikAnalysis
- `FinalDriversAnalysis` — extends AbstractAnalitikAnalysis
- `FinalFinanceAnalysis` — extends AbstractAnalitikAnalysis
- `FinalGroupAnomalyAnalysis` — extends AbstractAnomalyAnalysis
- `FinalQualityAnalysis` — extends AbstractAnalitikAnalysis
- `FinalRecordAnomalyAnalysis` — extends AbstractAnomalyAnalysis
- `FinalTrendAnalysis` — extends AbstractAnalitikAnalysis

## mathematics — статистика и математика

- `AbstractAnalitikMathematicsTest` — implements InterfaceSignificanceTest 
- `AbstractCorrelationTest` — extends AbstractAnalitikMathematicsTest
- `AbstractSegmentComparisonTest` — extends AbstractAnalitikMathematicsTest
- `EnumEffectScale`
- `FinalAnalitikMathematics`
- `FinalAnovaTest` — extends AbstractCorrelationTest
- `FinalChiSquareTest` — extends AbstractCorrelationTest
- `FinalMultipleComparisons`
- `FinalProbabilityDistributions`
- `FinalRegressionCoefficientTest` — extends AbstractCorrelationTest
- `FinalSpearmanTest` — extends AbstractCorrelationTest
- `FinalTwoProportionTest` — extends AbstractSegmentComparisonTest
- `FinalWelchTest` — extends AbstractSegmentComparisonTest
- `InterfaceSignificanceTest`

## formula — интерпретатор формул

- `EnumFormulaFunction`
- `EnumFormulaOperator`
- `FinalBinaryNode` — implements InterfaceFormulaNode 
- `FinalCellNode` — implements InterfaceFormulaNode 
- `FinalConstantNode` — implements InterfaceFormulaNode 
- `FinalFormulaParser`
- `FinalFormulaValues`
- `FinalFunctionNode` — implements InterfaceFormulaNode 
- `FinalUnaryNode` — implements InterfaceFormulaNode 
- `InterfaceFormulaNode`

## query — сводные и графики

- `FinalChartSpec`
- `FinalCondition`
- `FinalGrouping`
- `FinalPivotSpec`
- `FinalQueryEngine`

## data — данные

- `EnumColumnType`
- `FinalColumn`
- `FinalCsvParser` — implements InterfaceDataParser 
- `FinalDataLoader`
- `FinalDataset`
- `FinalDemoData`
- `FinalJsonParser` — implements InterfaceDataParser 
- `FinalRows`
- `InterfaceDataParser`

## graph — объекты графа (JSON)

- `FinalGraphBar`
- `FinalGraphEffect`
- `FinalGraphFilter`
- `FinalGraphInsight`
- `FinalGraphLink`
- `FinalGraphNode`
- `FinalGraphPayload`
- `FinalGraphRef`
- `FinalGraphTable`

## text — форматы

- `FinalFormatRu`

## exception — исключения

- `ExceptionAnalitikData` — extends IllegalArgumentException
- `ExceptionDatasetNotFound` — extends NoSuchElementException

## Иерархии

```
InterfaceSignificanceTest
└─ AbstractAnalitikMathematicsTest
   ├─ AbstractCorrelationTest
   │  ├─ FinalSpearmanTest
   │  ├─ FinalChiSquareTest
   │  ├─ FinalAnovaTest
   │  └─ FinalRegressionCoefficientTest
   └─ AbstractSegmentComparisonTest
      ├─ FinalWelchTest
      └─ FinalTwoProportionTest

AbstractAnalitikAnalysis
├─ FinalDriversAnalysis, FinalAbcAnalysis, FinalTrendAnalysis, FinalFinanceAnalysis, FinalQualityAnalysis
└─ AbstractAnomalyAnalysis
   ├─ FinalGroupAnomalyAnalysis
   └─ FinalRecordAnomalyAnalysis

InterfaceFormulaNode ← FinalConstantNode, FinalCellNode, FinalUnaryNode, FinalBinaryNode, FinalFunctionNode
InterfaceDataParser  ← FinalCsvParser, FinalJsonParser
InterfaceAiTool      ← 17 лямбд в ServiceAiTools
AbstractApiController ← ControllerBrainApi, ControllerAiApi
```
