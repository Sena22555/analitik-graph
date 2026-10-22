# Analitik Graph v2 — аналитический мозг

## Запуск
Двойной клик по `start.command` (или `bash start.command`) → откроется http://localhost:8080
Либо вручную: `java -jar dist/analitik-graph.jar`, или из исходников: `cd analytics-graph-engine && mvn spring-boot:run`

## v8 — граф как устройство
- **Сортировка несортированных данных** («+ Данные» → «Демо: несортированный лог», или кнопка «Разложить по таблицам» у таблицы):
  чистка (пробелы, пропуски, числа «1 290,00 ₽» → 1290, даты в ISO, варианты написания «МОСКВА» → «Москва», дубликаты),
  поиск функциональных зависимостей (ключ → колонки, ≥ 99,5 % строк), иерархия сущностей (Пользователи → Сессии → События).
  Окно показывает план с объяснением каждого шага; «Разложить» создаёт связанные таблицы.
- **Живой конвейер**: производные таблицы (из сортировки и из SQL) помнят источник (анимированные стрелки на графе)
  и пересчитываются сами, когда источник меняется (дописали строки, обновили по API), каскадом.
- **Собрать отчёт из графа**: разрезы и показатели из любых таблиц → один отчёт. Путь соединения граф находит сам по ключам,
  факты сворачиваются до клиента — суммы не раздуваются (fan trap); разрез из таблицы фактов × показатель из другой
  (chasm trap) считается честно и с пояснением; разрезы из двух таблиц фактов отклоняются с объяснением.
  Показан SQL, «Сохранить как таблицу» (появится на графе со стрелками происхождения), «Открыть в SQL», Excel.
- API: `GET|POST /api/v2/datasets/{id}/structure`, `POST /api/v2/demo/raw`, `POST /api/v2/sql/compose {dims, measures}`.

## v7 — удобство
- **Поиск ⌘K / Ctrl+K** (или `/`): таблицы, колонки всех таблиц, узлы открытого графа, разделы и действия.
  Enter на колонке — открыть её таблицу и выделить колонку на графе.
- **Меню «+ Данные»**: файл, API, таблица из SQL, демо-продукт, демо одной таблицы — с подписями.
- **«Данные и профиль»**: вкладка «Профиль колонок» — тип, заполненность, уникальные, статистика и мини-гистограмма,
  частые значения, замечания к данным (пропуски, константы, сильные выбросы); «Показать на графе».
- **Сайдбар**: секции сворачиваются (состояние запоминается); «Настройки графа» и «Как читать граф» свёрнуты по умолчанию.
- **Справка «?»** на графе: как читать узлы и линии, горячие клавиши.
- **«Сводные»**: выбор таблицы.
- ИИ-панель закрыта по умолчанию; раздел, открытая таблица и панель ИИ запоминаются между перезагрузками.

## v5 — рабочее пространство: несколько баз на одном графе
Подробный план развития — [ROADMAP.md](ROADMAP.md).

- **Демо-продукт** (кнопка «Демо» или первая карточка на старте): 4 связанные базы по «ID пользователя» —
  Пользователи, События на сайте, Платежи, Поддержка. В данные заложены эффекты, которые граф находит сам.
- **Граф** (вкладка): экран «Все таблицы» — карточки таблиц, на рёбрах число значимых связей между таблицами
  (сплошное — общий ключ, пунктир — через центральную таблицу); слева — главные связи между таблицами.
  Клик по карточке — «провал» в таблицу: весь экран становится её графом колонок (ветки раскрываются как раньше),
  сверху «← Все таблицы › Таблица» и кнопки «Данные» (строки: поиск, сортировка, страницы, Excel), «Сводная»,
  «Пути клиентов», «Спросить ИИ»; слева — связи с другими таблицами с переходом «→». Esc — назад ко всем таблицам.
- **SQL** (вкладка): запросы по всем таблицам на встроенной DuckDB — JOIN, оконные функции; имена таблиц и колонок
  в двойных кавычках (`SELECT "Источник трафика", COUNT(*) FROM "Пользователи" GROUP BY 1`). Слева схема (клик — вставить),
  примеры под ваши таблицы и история. Результат — таблицей или графиком, Excel, «Сохранить как таблицу» (появится на графе
  и обновляется своим запросом). Только чтение; доступ к файлам и сети из SQL выключен. ⌘/Ctrl + Enter — выполнить.
- **Пути** (вкладка): граф переходов по базе событий — доля клиентов на шаге, процент выхода (красная дуга),
  влияние шага на цель (z-тест долей, FDR), частые пути. Фильтр сегмента (из базы событий или базы клиентов),
  выбор цели, «кто эти люди» для шага / перехода / выхода; клик по отличию в профиле — сразу пути этого сегмента.
- **ИИ-аналитик** получил инструменты `workspace_atlas`, `journey`, `journey_profile` и видит список баз.
- **Сохранение:** базы переживают перезапуск — папка `workspace/` рядом с проектом (`-Danalitik.workspace=…` чтобы сменить).
  В файлах хранятся и заголовки API-источников (токены) — папка в `.gitignore`, не публикуйте её.
- **Безопасность:** сервер слушает только `127.0.0.1`, CORS — только localhost; смена адреса ИИ-провайдера без
  нового ключа сбрасывает сохранённый ключ.
- API: `GET /api/v2/workspace`, `GET /api/v2/workspace/graph`, `GET /api/v2/sql/schema`, `POST /api/v2/sql/query {sql, limit}`, `POST /api/v2/sql/save {sql, name}`, `POST /api/v2/sql/refresh/{id}`, `GET /api/v2/datasets/{id}/rows?offset&limit&q&sort&desc`, `POST /api/v2/demo/product`, `GET /api/v2/atlas?ids=a,b`,
  `POST /api/v2/journey {dataset?, goal?, segmentColumn?, segmentValue?, minShare?}`,
  `POST /api/v2/journey/profile {…, node | from+to | exitFrom}`.

## v4 — аналитик и бухгалтер, отчёты по ГОСТ, темы презентаций
- Новые инструменты аналитика: аудит качества данных, многофакторная регрессия (драйверы, с отсевом утечек и коллинеарности), ABC/Парето, динамика с прогнозом и интервалом, аномалии (MAD / 3×IQR), финансовая сводка и юнит-экономика (ARPU, ARPPU, средний чек, маржа, концентрация выручки, Джини).
- Официальный отчёт по ГОСТ 7.32-2017 → PDF (титульный лист, реферат, содержание с номерами страниц, нумерованные рисунки и таблицы, заключение, план мероприятий, приложение). Шрифт PT Serif, поля 30/15/20/20 мм.
- Презентации: 6 тем (Деловая, Финансовая, Редакционная, Ночная, Яркая, Минимализм), 14 макетов, смена темы в просмотре, экспорт в PowerPoint с нативными графиками и в PDF.
- Изображения генерирует отдельная модель (⚙ → «Генерация изображений», по умолчанию ищется gpt-image / dall-e / flux и т.п. у провайдера).
- Интерфейс: светлая и тёмная тема (кнопка в шапке).
- API: `POST /api/v2/datasets/{id}/analysis/{drivers|abc|trend|finance|quality|anomalies|compare|funnel|pivot}`, `POST /api/v2/ai/image`.

## ИИ-аналитик (v3)
Панель справа (кнопка «✦ ИИ-аналитик»). Модель считает не «из головы», а через инструменты сервера:
сводные таблицы, графики, сравнение сегментов со статистическими тестами, воронка, связи, новые колонки по формуле,
срезы данных, управление графом и сборка презентаций. Картинки (скриншоты, фото таблиц, снимок графа 📷) сначала
разбирает vision-модель.

- Настройки: ⚙ в шапке, переменные окружения `AI_BASE_URL` / `AI_API_KEY` / `AI_MODEL` или файл `analitik-ai.properties` в корне проекта (шаблон — `analitik-ai.properties.example`).
  Ключ живёт только на сервере. Файл добавлен в `.gitignore` — не коммитьте его.
- Подходит любой OpenAI-совместимый провайдер (`/chat/completions`, `/models`). Модель по умолчанию `gpt-4o-mini`: если у провайдера она называется иначе (например `openai/gpt-4o-mini`), сервер найдёт её сам.
- Вкладка «Сводные» — конструктор сводных без ИИ, экспорт в Excel/CSV. «Отчёты» — презентации, экспорт в PowerPoint (редактируемые графики) и PDF.

| Метод | Путь | |
|---|---|---|
| POST | /api/v2/ai/chat | чат (поток событий SSE) |
| GET/POST | /api/v2/ai/config | настройки ИИ |
| GET | /api/v2/ai/models | модели провайдера |
| POST | /api/v2/datasets/{id}/pivot | сводная `{rows, columns, values, agg, filters}` |
| POST | /api/v2/datasets/{id}/chart | график |
| POST | /api/v2/datasets/{id}/column | колонка по формуле `{name, formula}` |

## Как пользоваться
- **Подключить API** — URL на JSON (массив объектов / `{"data":[...]}`) или CSV, Google Sheets-ссылка; заголовки (токен) и автообновление.
- **CSV / JSON-файл** — кнопка или перетаскивание в окно.
- **Демо** — 1000 пользователей сайта.
- Клик по узлу раскрывает ветку как ментальную карту: измерение → сегменты (доля, 95% ДИ Уилсона) → KPI сегмента против остальных, воронка, вложенные измерения.
- Цвет связи = сила: розовый — сильная, жёлтый — средняя, синий — слабая; пунктир — обратная зависимость.

## Статистика
Спирмен (+ДИ Фишера), χ² + V Крамера (поправка Бергсмы), ANOVA + η²/ω², Краскел–Уоллис, t-тест Уэлча + g Хеджеса,
z-тест долей + h Коэна, Жарк–Бера, выбросы по Тьюки, гистограммы Фридмана–Диакониса,
контроль ложных открытий Бенджамини–Хохберга (показываются только связи с q < 0,05).

## REST API v2
| Метод | Путь | Что делает |
|---|---|---|
| POST | /api/v2/datasets | создать граф: `{name, csv}` / `{name, data:[...]}` / `{name, url, headers}` |
| GET | /api/v2/datasets/{id}/overview | мозг: узлы, связи, инсайты |
| POST | /api/v2/datasets/{id}/expand | раскрыть узел (тело = `ref` узла) |
| POST | /api/v2/datasets/{id}/append | дописать строки `{data:[...]}` — открытый граф обновится сам |
| POST | /api/v2/datasets/{id}/refresh | перечитать URL-источник |
| GET | /api/v2/demo/users?rows=1000&format=json\|csv&live=true | демо-источник |

```bash
curl -X POST http://localhost:8080/api/v2/datasets -H 'Content-Type: application/json' \
  -d '{"name":"Пользователи","url":"https://api.example.com/users","headers":{"Authorization":"Bearer TOKEN"}}'
```

---

# Analytics Graph Engine - примеры использования

## Запуск приложения

```bash
cd analytics-graph-engine
mvn spring-boot:run
```

Приложение запустится на `http://localhost:8080`

## API Endpoints

### 1. Создать новый граф (POST)

```bash
curl -X POST http://localhost:8080/api/v1/graphs \
  -H "Content-Type: application/json" \
  -d '{
    "csvData": "temperature,humidity,pressure\n23.5,45,1013.25\n24.1,48,1013.50\n23.8,46,1013.10\n24.5,49,1013.75\n23.2,44,1012.90",
    "name": "Weather Analytics"
  }'
```

**Response:**
```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "name": "Weather Analytics",
  "variables": {
    "temperature": { "name": "temperature", "type": "NUMERIC", "values": [23.5, 24.1, 23.8, 24.5, 23.2], ... },
    "humidity": { "name": "humidity", "type": "NUMERIC", "values": [45, 48, 46, 49, 44], ... },
    "pressure": { "name": "pressure", "type": "NUMERIC", "values": [1013.25, 1013.50, 1013.10, 1013.75, 1012.90], ... }
  },
  "timeSeries": [...],
  "statistics": {
    "variableCount": 3,
    "eventCount": 5,
    "edgeCount": 6,
    "density": 1.0,
    "avgCorrelation": 0.87,
    "strongestCorrelations": [...]
  }
}
```

### 2. Получить граф (GET)

```bash
curl http://localhost:8080/api/v1/graphs/{id}
```

### 3. Получить все графы (GET)

```bash
curl http://localhost:8080/api/v1/graphs
```

**Response:**
```json
{
  "graphs": [...],
  "count": 2
}
```

### 4. Получить статистику графа (GET)

```bash
curl http://localhost:8080/api/v1/graphs/{id}/statistics
```

**Response:**
```json
{
  "variableCount": 3,
  "eventCount": 5,
  "edgeCount": 6,
  "density": 1.0,
  "avgCorrelation": 0.87,
  "strongestCorrelations": [
    {
      "var1": "temperature",
      "var2": "humidity",
      "value": 0.92,
      "type": "positive"
    },
    {
      "var1": "temperature",
      "var2": "pressure",
      "value": 0.78,
      "type": "positive"
    }
  ],
  "temporalPatterns": [
    {
      "pattern": "data_point_0->data_point_1->data_point_2",
      "frequency": 2,
      "confidence": 0.4
    }
  ],
  "processingTimeMs": 45
}
```

### 5. Получить список переменных (GET)

```bash
curl http://localhost:8080/api/v1/graphs/{id}/variables
```

**Response:**
```json
{
  "variables": [
    { "name": "temperature", "type": "NUMERIC", "values": [...], "dependencies": ["humidity"], ... },
    { "name": "humidity", "type": "NUMERIC", "values": [...], "dependencies": ["temperature"], ... },
    { "name": "pressure", "type": "NUMERIC", "values": [...], "dependencies": [], ... }
  ],
  "count": 3
}
```

### 6. Получить информацию о переменной (GET)

```bash
curl http://localhost:8080/api/v1/graphs/{id}/variables/temperature
```

**Response:**
```json
{
  "name": "temperature",
  "type": "NUMERIC",
  "values": [23.5, 24.1, 23.8, 24.5, 23.2],
  "dependencies": ["humidity"],
  "correlations": {
    "humidity": 0.92,
    "pressure": 0.78
  },
  "metadata": {}
}
```

### 7. Получить матрицу корреляций (GET)

```bash
curl http://localhost:8080/api/v1/graphs/{id}/correlations
```

**Response:**
```json
{
  "matrix": {
    "temperature": { "humidity": 0.92, "pressure": 0.78 },
    "humidity": { "temperature": 0.92, "pressure": 0.65 },
    "pressure": { "temperature": 0.78, "humidity": 0.65 }
  },
  "strongestCorrelations": [
    { "var1": "temperature", "var2": "humidity", "value": 0.92, "type": "positive" },
    { "var1": "temperature", "var2": "pressure", "value": 0.78, "type": "positive" }
  ]
}
```

### 8. Получить временной ряд событий (GET)

```bash
curl "http://localhost:8080/api/v1/graphs/{id}/timeseries?skip=0&limit=10"
```

**Response:**
```json
{
  "events": [
    {
      "timestamp": 1694784480000,
      "eventType": "data_point_0",
      "value": 0,
      "variables": { "temperature": 23.5, "humidity": 45, "pressure": 1013.25 },
      "predecessors": [],
      "successors": ["data_point_1"],
      "severity": "MEDIUM"
    },
    {
      "timestamp": 1694784481000,
      "eventType": "data_point_1",
      "value": 0,
      "variables": { "temperature": 24.1, "humidity": 48, "pressure": 1013.50 },
      "predecessors": ["data_point_0"],
      "successors": ["data_point_2"],
      "severity": "MEDIUM"
    }
  ],
  "totalCount": 5
}
```

### 9. Получить матрицу смежности (GET)

```bash
curl http://localhost:8080/api/v1/graphs/{id}/adjacency
```

**Response:**
```json
{
  "temperature": { "humidity": 0.92, "pressure": 0.78 },
  "humidity": { "temperature": 0.92, "pressure": 0.65 },
  "pressure": { "temperature": 0.78, "humidity": 0.65 }
}
```

### 10. Удалить граф (DELETE)

```bash
curl -X DELETE http://localhost:8080/api/v1/graphs/{id}
```

**Response:**
```json
{
  "message": "Graph deleted: {id}",
  "timestamp": 1694784500000
}
```

---

## Сложный пример: анализ производственных данных

```bash
# Создаем граф для анализа производственных метрик
curl -X POST http://localhost:8080/api/v1/graphs \
  -H "Content-Type: application/json" \
  -d '{
    "csvData": "production_rate,defect_rate,temperature,humidity,machine_vibration\n95,2.1,62,55,0.8\n98,1.9,63,54,0.9\n92,3.2,61,58,1.2\n96,2.0,64,52,0.7\n99,1.5,65,51,0.6\n94,2.8,60,59,1.1\n97,1.8,63,53,0.8\n100,1.2,66,50,0.5",
    "name": "Production Metrics"
  }'
```

После создания графа вы сможете:
- Найти сильные корреляции между дефектами и условиями производства
- Выявить временные паттерны в данных
- Анализировать граф зависимостей переменных
- Получить статистику по каждой переменной

---

## Архитектура системы

### Компоненты

1. **AnalyticsGraph** - главный граф, объединяющий:
   - Граф зависимостей переменных
   - Временной ряд событий с причинно-следственными связями
   - Матрица смежности (adjacency matrix)
   - Статистика и паттерны

2. **Variable** - узел графа зависимостей:
   - Имя переменной
   - Тип (NUMERIC, CATEGORICAL, TEMPORAL, EVENT)
   - Значения
   - Зависимости от других переменных
   - Корреляции

3. **TimeSeriesEvent** - событие во временном ряду:
   - Временная метка
   - Тип события
   - Значение
   - Значения всех переменных в момент события
   - Предшественники/преемники (причинно-следственные связи)
   - Важность события (severity)

4. **AnalyticsGraphEngine** - движок анализа:
   - Вычисляет корреляции между переменными (Pearson)
   - Анализирует причинно-следственные связи между событиями
   - Вычисляет временные паттерны
   - Генерирует статистику графа

### Алгоритмы

- **Корреляции**: Pearson correlation coefficient (порог 0.5 для зависимостей)
- **Временные паттерны**: поиск повторяющихся последовательностей событий
- **Плотность графа**: edge_count / (n * (n-1)), где n - количество переменных
- **Причинно-следственные связи**: анализируются изменения значений между последовательными событиями

---

## Использование в Java коде

```java
@Autowired
private AnalyticsGraphEngine engine;

// Создаем граф
String csvData = "var1,var2,var3\n1,2,3\n4,5,6\n...";
AnalyticsGraph graph = engine.buildGraph(csvData, "My Graph");

// Работаем с переменными
Variable var = graph.getVariable("var1");
Set<String> dependencies = var.getDependencies();
Map<String, Double> correlations = var.getCorrelations();

// Анализируем статистику
GraphStatistics stats = graph.getStatistics();
List<GraphStatistics.Correlation> strongCorr = stats.getStrongestCorrelations();
List<GraphStatistics.TemporalPattern> patterns = stats.getTemporalPatterns();

// Работаем с временным рядом
List<TimeSeriesEvent> events = graph.getTimeSeries();
for (TimeSeriesEvent event : events) {
    Map<String, Double> vars = event.getVariables();
    List<String> predecessors = event.getPredecessors();
}
```

---

## Примечания

- Данные нормализуются перед анализом (z-score)
- Все корреляции вычисляются только для числовых переменных
- Временные события создаются с интервалом 1 сек между точками
- Матрица смежности содержит абсолютные значения корреляций
- Сильные корреляции (>0.7) включаются в зависимости переменных
