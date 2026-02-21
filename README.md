# Receipto 🧾

Receipto is a lightweight, offline-first smart receipt reader for Android. It uses a combination of classical computer vision and a localized TensorFlow Lite Key Information Extraction (KIE) engine to extract structured financial data (Store Name, Date, Amounts, Tax, etc.) completely on-device.

No cloud APIs. No privacy concerns. Fully offline.

## Screenshots

Simple preview of main app flow.

<p align="center">
  <img src="https://github.com/user-attachments/assets/d9688ecf-69bb-491f-8b3d-6c5f5ca6953b" width="200"/>
  <img src="https://github.com/user-attachments/assets/c7ae88ff-2641-4065-8b90-837196c77c93" width="200"/>
  <img src="https://github.com/user-attachments/assets/8a9d5c12-aa1d-433b-8e33-c5a6c266e240" width="200"/>
</p>

<p align="center">
  <img src="https://github.com/user-attachments/assets/4479693a-bf9f-421e-90c5-624ff8a7eba4" width="200"/>
  <img src="https://github.com/user-attachments/assets/92aee4ae-1d66-4743-9f54-4e3b816b5dc6" width="200"/>
  <img src="https://github.com/user-attachments/assets/2cad8528-b5f9-40ae-b719-6d7728da49e2" width="200"/>
</p>

---

## Core Features
*   **On-Device Processing**: Extracts data using ML Kit Text Recognition and custom KIE/heuristic algorithms without needing an internet connection.
*   **Layout-Aware OCR Parsing**: Intelligently groups bounding boxes from raw text to reconstruct logical lines and accurately pair prices to items, even on crinkled receipts.
*   **Offline Persistence**: Automatically saves extracted `ReceiptData` to a local Room Database for seamless retrieval and history.
*   **Fallback Heuristics**: Deterministic spatial fallback rules (e.g. finding the rightmost numbers on lines matching 'TOTAL') handle chaotic layouts when ML prediction confidence is low.

## How it Works

The pipeline is designed to be deterministic and layout-aware, prioritizing predictable results over purely black-box ML predictions:

1.  **Image Capture & Preprocessing**: The camera captures an image, which is passed to a localized OpenCV pipeline. Crucially, we apply gentle noise reduction (Gaussian blur) but *skip* binarization and deskewing. This preserves the anti-aliased edge data required for ML Kit's internal deep learning accuracy.
2.  **Raw Token Extraction**: Google's ML Kit Text Recognition processes the image, returning raw text tokens along with their geometric bounding boxes.
3.  **Token Merging (The UI-Decoupled Brain)**: The `KieReceiptParser` (decoupled from the Compose UI) groups these scattered tokens based on their Y-axis center-points to form logical horizontal lines, effectively reconstructing the receipt's physical layout.
4.  **Key Information Extraction (KIE)**: An on-device TensorFlow Lite model attempts to tag items, prices, totals, and dates.
5.  **Spatial Heuristics (Fallback)**: Since receipts are highly non-standard, if the KIE tags are missing or lack confidence, a robust set of regular expressions and spatial rules takes over. For example, it searches for text matching "TOTAL" and directly extracts the right-most numeric token on that exact physical line.
6.  **Persistence**: The parsed `ReceiptData` object is mapped to a Room `ReceiptEntity` and persisted asynchronously via a decoupled Repository pattern.

## Architecture

The project adheres to Clean Architecture and Separation of Concerns:
*   **UI Layer (Jetpack Compose)**: `CameraScreen.kt` focuses strictly on camera lifecycles, permissions, and displaying parsing results.
*   **Domain / Extraction Engine**: `KieReceiptParser.kt` serves as the core logic boundary, encapsulating complex NLP token merging, bounding-box geometry calculations, and regex amount extraction. 
*   **Data Persistence**: A structured Room implementation (`ReceiptDatabase`, `ReceiptEntity`, `ReceiptRepository`) locally manages the persistence of scanned history.

## Tech Stack & Libraries

*   **UI Framework**: [Jetpack Compose](https://developer.android.com/jetpack/compose) for a declarative, reactive UI.
*   **Computer Vision**: [OpenCV for Android](https://opencv.org/android/) (included directly via C++ SDK) for image noise reduction and preprocessing.
*   **OCR Engine**: [Google ML Kit Text Recognition](https://developers.google.com/ml-kit/vision/text-recognition/android) for raw bounding box and token extraction.
*   **Inference Engine**: [TensorFlow Lite](https://www.tensorflow.org/lite) for executing the localized Key Information Extraction (KIE) tagging model.
*   **Local Persistence**: [Room Database](https://developer.android.com/training/data-storage/room) mapping parsed objects into immutable local storage.
*   **Architecture**: MVVM with Clean Architecture decoupling the UI, Domain (parsers), and Data (repositories).

## Project Structure

```text
Receipto/
├── app/src/main/
│   ├── assets/models/      # Offline TFLite models and tag dictionaries
│   ├── java/.../receipto/
│   │   ├── data/           # Room Database, Daos, and Entities
│   │   ├── kie/            # KIE Runtime and token mapping
│   │   ├── ocr/            # OpenCV ImagePreprocessor and ML Kit OcrProcessor
│   │   ├── parser/         # Core extraction logic (KieReceiptParser)
│   │   ├── repository/     # Data repository logic
│   │   └── ui/             # Jetpack Compose screens (CameraScreen, etc.)
├── sdk/                    # Locally bundled OpenCV Android SDK
└── README.md
```
## Limitations

*   **Non-standard Layouts**: Highly irregular receipts (e.g., hand-written notes mixed with printed text, or extreme fading beyond human readability) may result in missed fields.
*   **Local Processing Only**: Since there is no cloud fallback, devices with extremely constrained memory/computational power might experience slightly longer processing times (typically under a few seconds).
*   **Currency Formats**: The current deterministic fallback rules are heavily optimized for US and common EU formatting (`,` vs `.`). Highly localized global formats may require additional heuristic rules.

## Getting Started

### Prerequisites
*   Android Studio Ladybug (or newer recommended)
*   Android SDK 33
*   JDK 17

### Building the Project
1. Clone the repository.
2. Open the project in Android Studio.
3. Sync the Gradle files. 
4. Run the app on an emulator or physical device via the `assembleDebug` configuration.

## Contributing
Pull requests are welcome. For major changes, please open an issue first to discuss what you would like to change. Please make sure to update tests as appropriate.

## License
[MIT](https://choosealicense.com/licenses/mit/)
