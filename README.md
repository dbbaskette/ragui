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

#### Secure Credentials

For security, default user credentials are now managed through a non-tracked properties file. The build process handles this automatically:

**Option 1: Environment Variables (Recommended)**
```bash
export DEFAULT_USERNAME=your_username
export DEFAULT_PASSWORD=your_secure_password
./build-secure.sh
```

**Option 2: Manual Configuration**
1. Copy the template: `cp src/main/resources/application-secure.properties.template src/main/resources/application-secure.properties`
2. Edit the file with your credentials
3. Run: `./build-secure.sh`

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

### Local Development
To run the application locally:
```bash
./mvnw spring-boot:run
```
The application will be available at `http://localhost:8080`.

### Secure Build
For production builds with secure credentials:
```bash
./build-secure.sh
```

## Deployment

### Quick Deployment
Use the `deploy.sh` script for streamlined build and deployment:
```bash
./deploy.sh          # Build and deploy
./deploy.sh --help   # See all options
```

### Secure Deployment
For deployments with secure credentials:
```bash
./deploy-secure.sh   # Build and deploy with secure credentials
```

### Advanced Deployment
The `manifest.yml` is included for deployment to Cloud Foundry. The `release.sh` script can be used to automate the release process with versioning.

## Contributing

Contributions are welcome! Please feel free to submit a pull request.

## License

This project is licensed under the MIT License.
