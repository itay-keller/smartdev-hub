# SmartDev Hub

A developer productivity platform built with Spring Boot 3 + SQLite.  
This project is the base for an AI agents learning course — each issue in `/issues` is solved by an AI agent.

## Stack

- Java 21
- Spring Boot 3.2
- SQLite (via Hibernate Community Dialects)
- JUnit 5 + Mockito
- Maven

## Running locally

```bash
# Start the app
mvn spring-boot:run

# Run tests
mvn test
```

The app starts on `http://localhost:8080`.  
The SQLite database file `smartdev.db` is created automatically on first run.

## API endpoints

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/tasks` | List all tasks (optional `?status=TODO`) |
| POST | `/api/tasks` | Create a task |
| PUT | `/api/tasks/{id}` | Update a task |
| DELETE | `/api/tasks/{id}` | Delete a task |
| GET | `/api/tasks/sorted` | Tasks sorted by priority ⚠️ has a known bug |
| GET | `/api/bugs` | List all bugs (optional `?severity=HIGH`) |
| POST | `/api/bugs` | Report a bug |
| PATCH | `/api/bugs/{id}/status` | Update bug status |
| GET | `/api/flags` | List feature flags |
| POST | `/api/flags` | Create a feature flag |
| POST | `/api/flags/{name}/toggle` | Toggle a flag on/off |

## Known issues (for agent exercises)

- `issue-001.json` — Add pagination to GET /api/tasks
- `issue-002.json` — NPE in /api/tasks/sorted when priority is null  ← **start here**
- `issue-003.json` — Missing GET /api/activity endpoint
- `issue-004.json` — MCP server needs getBugStats tool

## Project structure

```
src/
  main/java/com/smartdev/hub/
    entity/          # Task, Bug, FeatureFlag, ActivityLog
    repository/      # Spring Data JPA repositories
    service/         # Business logic
    controller/      # REST controllers
  resources/
    application.properties
issues/              # Fake GitHub issues — agent input files
```

## Ollama / AI agent config

The app is pre-wired to talk to Ollama on `http://localhost:11434` using the `mistral` model.  
See `application.properties` to change the model.

Make sure Ollama is running before starting agent exercises:
```bash
ollama serve
```
