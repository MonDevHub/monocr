use std::io::Cursor;
use wasm_bindgen::prelude::*;
use image::ImageReader;
use image::imageops::{resize, overlay, FilterType};
use image::{GrayImage, Luma};
use rten::Model;
use ocrs::ImageSource;
use rten_tensor::{NdTensor, AsView};
use rten_imageproc::{Rect, RotatedRect};

#[wasm_bindgen]
pub struct MonOcr {
    engine: ocrs::OcrEngine,
}

#[wasm_bindgen]
impl MonOcr {
    #[wasm_bindgen(constructor)]
    pub fn new(detection_model_data: Option<Vec<u8>>, recognition_model_data: Vec<u8>, charset: String) -> Result<MonOcr, JsValue> {
        console_error_panic_hook::set_once();

        let recognition_model = Model::load(recognition_model_data).map_err(|e| format!("Failed to load recognition model: {}", e))?;
        
        let detection_model = if let Some(data) = detection_model_data {
            Some(Model::load(data).map_err(|e| format!("Failed to load detection model: {}", e))?)
        } else {
            None
        };

        let params = ocrs::OcrEngineParams {
            detection_model,
            recognition_model: Some(recognition_model),
            alphabet: Some(charset),
            ..Default::default()
        };

        let engine = ocrs::OcrEngine::new(params).map_err(|e| format!("Failed to create OcrEngine: {}", e))?;

        Ok(MonOcr { engine })
    }

    pub fn recognize(&self, image_data: &[u8]) -> Result<String, JsValue> {
        let img = ImageReader::new(Cursor::new(image_data))
            .with_guessed_format()
            .map_err(|e| format!("Failed to determine image format: {}", e))?
            .decode()
            .map_err(|e| format!("Failed to decode image: {}", e))?;

        // Preprocess image to match model requirements (64x1024, L, [-1,1])
        let (tensor_data, h, w) = self.preprocess(&img);

        // Use NdTensor to enforce rank 3 [Channels, Height, Width]
        let tensor = NdTensor::from_data(
            [1, h, w], // 1 Channel (Grayscale)
            tensor_data
        );

        let img_source = ImageSource::from_tensor(tensor.nd_view(), ocrs::DimOrder::Chw)
             .map_err(|e| JsValue::from_str(&format!("Failed to create ImageSource: {}", e)))?;

        let input = self.engine.prepare_input(img_source)
            .map_err(|e| format!("Failed to prepare input: {}", e))?;
        
        let text = if self.engine.detect_words(&input).is_ok() {
            // Detection model is loaded and working
            self.engine.get_text(&input)
                .map_err(|e| format!("OCR execution failed: {}", e))?
        } else {
            // Detection failed or model not loaded - fall back to full-image line recognition
            let line_rect = Rect::from_tlhw(0, 0, h as i32, w as i32).to_f32();
            let rotated_rect = RotatedRect::from_rect(line_rect);
            let line_regions = vec![vec![rotated_rect]];
            
            self.engine.recognize_text(&input, &line_regions)
                .map_err(|e| format!("OCR execution failed: {}", e))?
                .into_iter()
                .filter_map(|line| line.map(|l| l.to_string()))
                .collect::<Vec<_>>()
                .join("\n")
        };
            
        Ok(text)
    }

    /// Preprocesses the input image to match the MonOCR model requirements:
    /// - Convert to Grayscale
    /// - Resize to 64px height (preserving aspect ratio)
    /// - Pad to 1024px width
    /// - Normalize to [-1, 1] range (encoded for ocrs prepare_image)
    fn preprocess(&self, img: &image::DynamicImage) -> (Vec<f32>, usize, usize) {
        // 1. Convert to Grayscale
        let img_gray = img.to_luma8();
        let (width, height) = img_gray.dimensions();

        if width == 0 || height == 0 {
             // Return empty tensor data or handle error more gracefully?
             // Since we return (Vec, usize, usize), lets just return small black image to avoid panic, 
             // or better: this function should probably return Result.
             // But to keep it simple and given the signature:
             // We'll treat it as a 1x1 black pixel which will result in empty result.
             return (vec![-0.5], 1, 1);
        }

        // 2. Resize to height 128, maintaining aspect ratio
        let target_h = 128;
        let scale = target_h as f32 / height as f32;
        let new_w = (width as f32 * scale) as u32;
        let target_w = 1024;
        let final_w = if new_w > target_w { target_w } else { new_w };

        let resized = resize(&img_gray, final_w, target_h, FilterType::Triangle);

        // 3. Pad to 1024 width (White background)
        let mut canvas = GrayImage::from_pixel(target_w, target_h, Luma([255]));
        overlay(&mut canvas, &resized, 0, 0);

        // 4. Normalization to produce [-1, 1] output from ocrs::prepare_image
        // ocrs does: pixel_val - 0.5
        // We want: (p / 127.5) - 1.0
        // EQUATION: pixel_val - 0.5 = (p / 127.5) - 1.0
        // RESULT: pixel_val = (p / 127.5) - 0.5
        let (can_w, can_h) = canvas.dimensions();
        let mut data = Vec::with_capacity((can_w * can_h) as usize);
        
        for pixel in canvas.pixels() {
            let p = pixel.0[0] as f32;
            data.push((p / 127.5) - 0.5);
        }

        (data, can_h as usize, can_w as usize)
    }
}
