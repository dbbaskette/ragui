# RAGUI: Retrieval-Augmented Generation UI

RAGUI is a Spring Boot application that provides a web-based user interface for Retrieval-Augmented Generation (RAG) tasks. It leverages Spring AI to interact with OpenAI's models and uses a PostgreSQL database with the pgvector extension for efficient vector storage and retrieval. The application is designed to be deployed on cloud platforms and includes features for managing long-running jobs, monitoring application status, and handling configuration.

## Features

*   **Retrieval-Augmented Generation (RAG):** Core functionality for performing RAG tasks using Spring AI and OpenAI.
*   **Job Management:** Asynchronous job processing for long-running tasks.
*   **Vector Store:** Uses PostgreSQL with pgvector for storing and querying document embeddings.
*   **Configuration Management:** Externalized configuration using Spring Cloud Config.
*   **Security:** Secured endpoints using Spring Security.
*   **Monitoring:** Status and version endpoints for monitoring application health.
*   **Web UI:** A simple front-end for interacting with the RAG service.

## Technologies Used

*   **Backend:** Java, Spring Boot, Spring AI, Spring Data JPA, Spring Security, Spring Cloud Config
*   **Database:** PostgreSQL with pgvector
*   **AI Model:** OpenAI
*   **Build:** Maven
*   **Deployment:** Cloud Foundry (or other cloud platforms)

## Getting Started

### Prerequisites

*   Java 17 or later
*   Maven 3.6.3 or later
*   PostgreSQL with the pgvector extension installed
*   An OpenAI API key
*   Access to a Spring Cloud Config server (optional, for production deployments)

### Installation

1.  Clone the repository:
    ```bash
    git clone https://github.com/dbbaskette/ragui.git
    cd ragui
    ```
2.  Build the project:
    ```bash
    ./mvnw clean install
    ```

### Configuration

The application requires connection details for the PostgreSQL database and the OpenAI API key. These can be configured in `src/main/resources/application.properties` for local development or through environment variables for cloud deployments.

**`application.properties` example:**
```properties
# PostgreSQL Configuration
spring.datasource.url=jdbc:postgresql://localhost:5432/your-db
spring.datasource.username=your-username
spring.datasource.password=your-password

# OpenAI Configuration
spring.ai.openai.api-key=YOUR_OPENAI_API_KEY
```

## Usage

The application exposes several REST endpoints for interacting with the RAG service and managing the application.

*   `POST /chat`: The main endpoint for submitting a query to the RAG service.
*   `GET /status`: Returns the current status of the application.
*   `GET /version`: Returns the application version information.
*   `GET /jobs`: Lists all jobs.
*   `GET /jobs/{id}`: Gets the status of a specific job.

## Building and Running

To run the application locally:
```bash
./mvnw spring-boot:run
```
The application will be available at `http://localhost:8080`.

## Deployment

The `manifest.yml` is included for deployment to Cloud Foundry. The `release.sh` script can be used to automate the release process.

## Contributing

Contributions are welcome! Please feel free to submit a pull request.

## License

This project is licensed under the MIT License.
