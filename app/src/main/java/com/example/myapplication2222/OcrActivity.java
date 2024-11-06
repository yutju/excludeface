package com.example.myapplication2222;




import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.util.Size;
import android.view.Display;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleOwner;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions;

import java.io.File;
import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class OcrActivity extends AppCompatActivity {

    private static final int PERMISSION_REQUEST_CODE = 2001;
    private static final int REQUEST_CODE_CAPTURE_IMAGE = 1001;

    private TextView resultTextView;
    private TextView adultVerificationResult;
    private TextView dobExtracted;
    private TextView issueDateTextView;
    private LinearLayout adultVerificationLayout;
    private LinearLayout nameVerificationLayout;
    private TextView loggedInUserName;
    private TextView idCardName;
    private TextView nameComparisonResult;
    private ImageView imageView;
    private ProgressBar progressBar;
    private PreviewView previewView;
    private ImageCapture imageCapture;
    private ExecutorService cameraExecutor;
    private File photoFile;
    private ProcessCameraProvider cameraProvider;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ocr);

        // UI 구성 요소 초기화
        Button captureButton = findViewById(R.id.captureButton);
        Button recaptureButton = findViewById(R.id.recaptureButton);
        resultTextView = findViewById(R.id.resultTextView);
        adultVerificationLayout = findViewById(R.id.adultVerificationLayout);
        nameVerificationLayout = findViewById(R.id.nameVerificationLayout);
        dobExtracted = findViewById(R.id.dobExtracted);
        issueDateTextView = findViewById(R.id.issueDateTextView);

        loggedInUserName = findViewById(R.id.loggedInUserName);
        idCardName = findViewById(R.id.idCardName);
        nameComparisonResult = findViewById(R.id.nameComparisonResult);
        adultVerificationResult = findViewById(R.id.adultVerificationResult);
        imageView = findViewById(R.id.imageView);
        progressBar = findViewById(R.id.progressBar);
        previewView = findViewById(R.id.previewView);

        // 카메라 실행기 초기화
        cameraExecutor = Executors.newSingleThreadExecutor();

        // 화면 크기에 맞게 레이아웃 조정
        adjustLayoutForScreenSize();

        // 카메라 권한 확인
        if (allPermissionsGranted()) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, PERMISSION_REQUEST_CODE);
        }

        // 버튼 리스너 설정
        captureButton.setOnClickListener(v -> takePhoto());
        recaptureButton.setOnClickListener(v -> startCamera());

        // 초기에는 성인 인증 및 본인 인증 레이아웃을 숨김
        adultVerificationLayout.setVisibility(View.GONE);
        nameVerificationLayout.setVisibility(View.GONE);
    }

    // 화면 크기에 맞게 레이아웃 조정
    private void adjustLayoutForScreenSize() {
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        Display display = wm.getDefaultDisplay();
        android.graphics.Point size = new android.graphics.Point();
        display.getSize(size);
        int screenWidth = size.x;
        int screenHeight = size.y;

        // 16:9 비율로 높이 계산
        int viewWidth = screenWidth / 2;
        int viewHeight = (viewWidth * 9) / 16;

        // PreviewView 및 ImageView의 너비와 높이 설정
        ViewGroup.LayoutParams previewLayoutParams = previewView.getLayoutParams();
        previewLayoutParams.width = viewWidth;
        previewLayoutParams.height = viewHeight;
        previewView.setLayoutParams(previewLayoutParams);

        ViewGroup.LayoutParams imageViewLayoutParams = imageView.getLayoutParams();
        imageViewLayoutParams.width = viewWidth;
        imageViewLayoutParams.height = viewHeight;
        imageView.setLayoutParams(imageViewLayoutParams);
    }

    // 모든 권한이 부여되었는지 확인
    private boolean allPermissionsGranted() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    // 카메라 시작 및 생명주기에 바인딩
    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(() -> {
            try {
                cameraProvider = cameraProviderFuture.get();
                bindPreview(cameraProvider);
            } catch (ExecutionException | InterruptedException e) {
                Log.e("OcrActivity", "카메라 초기화 실패", e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    // 카메라 생명주기에 미리보기 및 이미지 캡처 바인딩
    private void bindPreview(ProcessCameraProvider cameraProvider) {
        previewView.post(() -> {
            int previewWidth = previewView.getMeasuredWidth();
            int previewHeight = previewView.getMeasuredHeight();

            Preview preview = new Preview.Builder()
                    .setTargetResolution(new Size(previewWidth, previewHeight))
                    .build();
            preview.setSurfaceProvider(previewView.getSurfaceProvider());

            imageCapture = new ImageCapture.Builder()
                    .setTargetResolution(new Size(previewWidth, previewHeight))
                    .setTargetRotation(previewView.getDisplay().getRotation())
                    .build();

            CameraSelector cameraSelector = new CameraSelector.Builder()
                    .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                    .build();

            cameraProvider.unbindAll();
            cameraProvider.bindToLifecycle((LifecycleOwner) this, cameraSelector, preview, imageCapture);
        });
    }

    // 사진 캡처 및 저장
    private void takePhoto() {
        if (imageCapture == null) return;

        photoFile = new File(getExternalFilesDir(null), "photo.jpg");
        ImageCapture.OutputFileOptions outputOptions = new ImageCapture.OutputFileOptions.Builder(photoFile).build();

        imageCapture.takePicture(outputOptions, cameraExecutor, new ImageCapture.OnImageSavedCallback() {
            @Override
            public void onImageSaved(@NonNull ImageCapture.OutputFileResults outputFileResults) {
                runOnUiThread(() -> {
                    Toast.makeText(OcrActivity.this, "사진이 저장되었습니다.", Toast.LENGTH_SHORT).show();
                    updateImageView(Uri.fromFile(photoFile));
                    cameraProvider.unbindAll();
                });
                Log.d("OcrActivity", "사진 저장 위치: " + photoFile.getAbsolutePath());
                processImage(Uri.fromFile(photoFile));
            }

            @Override
            public void onError(@NonNull ImageCaptureException exception) {
                runOnUiThread(() -> Toast.makeText(OcrActivity.this, "사진 캡처 실패", Toast.LENGTH_SHORT).show());
                Log.e("OcrActivity", "사진 캡처 실패", exception);
            }
        });
    }

    // 캡처한 사진으로 ImageView 업데이트
    private void updateImageView(Uri photoUri) {
        try {
            Bitmap bitmap = BitmapFactory.decodeStream(getContentResolver().openInputStream(photoUri));
            imageView.setImageBitmap(bitmap);
        } catch (IOException e) {
            e.printStackTrace();
            Toast.makeText(OcrActivity.this, "이미지 로드 실패", Toast.LENGTH_SHORT).show();
        }
    }

    // OCR 후 이미지 처리 개선
    private void processImage(Uri imageUri) {
        try {
            InputImage image = InputImage.fromFilePath(this, imageUri);
            TextRecognizer recognizer = TextRecognition.getClient(new KoreanTextRecognizerOptions.Builder().build());

            recognizer.process(image)
                    .addOnSuccessListener(text -> {
                        String recognizedText = text.getText();
                        Log.d("OcrActivity", "인식된 텍스트 (공백 제거 전): " + recognizedText);

                        // 생년월일, 이름, 주민등록번호, 신분증 발급일자 추출
                        String dob = findDateOfBirth(recognizedText);
                        String name = findName(recognizedText);
                        String ssn = findSSN(recognizedText);
                        Log.d("OcrActivity", "주민등록번호 (공백 제거 후): " + ssn); // 공백 제거된 주민등록번호 로그

                        String issueDate = findIssueDate(recognizedText);

                        if (ssn != null) {
                            ssn = ssn.replaceAll("\\s+", "").replaceAll("-", ""); // 공백과 하이픈 제거
                        }

                        FirebaseUser currentUser = FirebaseAuth.getInstance().getCurrentUser();

                        // 주민등록번호에 하이픈 추가
                        String ssnWithHyphen = addHyphenToSSN(ssn);
                        Log.d("OcrActivity", "하이픈 추가된 주민등록번호: " + ssnWithHyphen); // 하이픈 추가 후 로그

                        runOnUiThread(() -> {
                            if (dob != null && !isMinor(dob) && issueDate != null) {
                                performAdultVerification(dob, name, ssnWithHyphen, currentUser, issueDate);
                            } else if (dob != null) {
                                resultTextView.setText("미성년자입니다.");
                                Toast.makeText(OcrActivity.this, "미성년자이시거나 생년월일이 제대로 추출되지 않았습니다.", Toast.LENGTH_SHORT).show();
                            } else {
                                resultTextView.setText("생년월일을 찾을 수 없습니다.");
                                Toast.makeText(OcrActivity.this, "생년월일을 찾을 수 없습니다. 신분증을 다시 확인해주세요.", Toast.LENGTH_SHORT).show();
                            }
                        });
                    })
                    .addOnFailureListener(e -> {
                        Log.e("OcrActivity", "텍스트 인식 실패", e);
                        runOnUiThread(() -> {
                            resultTextView.setText("텍스트 인식 실패");
                            Toast.makeText(OcrActivity.this, "텍스트 인식 실패. 다시 시도해주세요.", Toast.LENGTH_SHORT).show();
                        });
                    });
        } catch (IOException e) {
            Log.e("OcrActivity", "이미지 처리 실패", e);
            runOnUiThread(() -> {
                resultTextView.setText("이미지 처리 실패");
                Toast.makeText(OcrActivity.this, "이미지 처리 실패. 다시 시도해주세요.", Toast.LENGTH_SHORT).show();
            });
        }
    }

    // 주민등록번호에 하이픈 추가
    private String addHyphenToSSN(String ssn) {
        if (ssn != null && ssn.length() == 13) {
            return ssn.substring(0, 6) + "-" + ssn.substring(6);
        }
        return null;
    }

    // 성인 인증 절차 수행
    private void performAdultVerification(String dob, String name, String ssnWithHyphen, FirebaseUser currentUser, String issueDate) {
        adultVerificationLayout.setVisibility(View.VISIBLE);
        adultVerificationResult.setText("1차 성인 인증을 진행합니다...");



        new Handler().postDelayed(() -> {
            dobExtracted.setText("추출된 생년월일: " + dob);
            dobExtracted.setVisibility(View.VISIBLE);
            issueDateTextView.setText("추출된 발급일자: " + issueDate); // 발급일자 UI에 설정
            issueDateTextView.setVisibility(View.VISIBLE); // UI에 보이게 설정
            adultVerificationResult.setText("1차 성인 인증이 완료되었습니다.");

            nameComparisonResult.setText("2차 본인 인증을 진행합니다...");
            nameVerificationLayout.setVisibility(View.VISIBLE);

            new Handler().postDelayed(() -> {
                if (name != null && ssnWithHyphen != null && currentUser != null) {
                    getCurrentUserSSN(currentUser.getUid(), currentUserSSN -> {
                        if (currentUserSSN != null) {
                            currentUserSSN = currentUserSSN.replace("-", ""); // 하이픈 제거
                        }

                        final String currentUserName = currentUser.getDisplayName();
                        boolean isNameMatch = currentUserName != null && currentUserName.equalsIgnoreCase(name);
                        boolean isSSNMatch = currentUserSSN != null && currentUserSSN.equals(ssnWithHyphen.replace("-", ""));

                        // Firestore에서 로그인된 사용자의 발급일자 가져오기
                        getUserIssueDate(currentUser.getUid(), currentUserIssueDate -> {
                            runOnUiThread(() -> {
                                idCardName.setText("신분증 이름: " + name);
                                loggedInUserName.setText("로그인된 사용자 이름: " + currentUserName);

                                if (isNameMatch && isSSNMatch) {
                                    // 발급일자 비교
                                    boolean isIssueDateMatch = currentUserIssueDate != null && currentUserIssueDate.equals(issueDate);
                                    if (isIssueDateMatch) {
                                        nameComparisonResult.setText("2차 본인 인증이 완료되었습니다.");
                                        // FaceVerificationActivity로 이동
                                        Intent intent = new Intent(OcrActivity.this,OrderSummaryActivity.class);
                                        startActivity(intent);
                                        finish();
                                    } else {
                                        nameComparisonResult.setText("본인 인증 실패: 발급일자가 일치하지 않습니다.");
                                    }
                                } else {
                                    nameComparisonResult.setText("본인 인증 실패: 이름 또는 주민등록번호가 일치하지 않습니다.");
                                }
                            });
                        });
                    });
                } else {
                    runOnUiThread(() -> {
                        resultTextView.setText("본인 인증 실패: 이름 또는 주민등록번호를 확인할 수 없습니다.");
                        Toast.makeText(OcrActivity.this, "본인 인증 실패: 이름 또는 주민등록번호를 확인할 수 없습니다.", Toast.LENGTH_SHORT).show();
                    });
                }
            }, 1500); // 1.5초 후 2차 본인 인증 진행
        }, 1500); // 1.5초 후 1차 성인 인증 완료 메시지
    }

    // Firestore에서 사용자의 발급일자를 가져오는 메서드 (콜백 방식으로 수정)
    private void getUserIssueDate(String userId, FirestoreCallbackIssueDate callback) {
        FirebaseFirestore db = FirebaseFirestore.getInstance();
        DocumentReference userRef = db.collection("users").document(userId);

        userRef.get().addOnSuccessListener(documentSnapshot -> {
            if (documentSnapshot.exists()) {
                String issueDate = documentSnapshot.getString("issueDate"); // 발급일자 가져오기
                callback.onCallback(issueDate);
            } else {
                callback.onCallback(null);
            }
        }).addOnFailureListener(e -> {
            Log.e("OcrActivity", "사용자 발급일자 가져오기 실패", e);
            callback.onCallback(null);
        });
    }

    // FirestoreCallback 인터페이스의 수정
    private interface FirestoreCallbackIssueDate {
        void onCallback(String issueDate);
    }


    // Firestore에서 사용자의 주민등록번호를 가져오는 메서드 (콜백 방식으로 수정)
    private void getCurrentUserSSN(String userId, FirestoreCallback callback) {
        FirebaseFirestore db = FirebaseFirestore.getInstance();
        DocumentReference userRef = db.collection("users").document(userId);

        userRef.get().addOnSuccessListener(documentSnapshot -> {
            if (documentSnapshot.exists()) {
                String ssn = documentSnapshot.getString("ssn");
                callback.onCallback(ssn);
            } else {
                callback.onCallback(null);
            }
        }).addOnFailureListener(e -> {
            Log.e("OcrActivity", "사용자 주민등록번호 가져오기 실패", e);
            callback.onCallback(null);
        });
    }

    private interface FirestoreCallback {
        void onCallback(String ssn);
    }

    // 주민등록번호 찾기 추가
    private String findSSN(String text) {
        // 전체 텍스트에서 모든 공백 제거
        text = text.replaceAll("\\s+", "");  // 텍스트 전체의 공백 제거

        // 주민등록번호 형식 (YYMMDD-XXXXXXX) 또는 하이픈이 없는 형식도 허용
        Pattern ssnPattern = Pattern.compile("\\d{6}-?\\d{7}");
        Matcher ssnMatcher = ssnPattern.matcher(text);

        if (ssnMatcher.find()) {
            // 추출된 주민등록번호에서 하이픈과 공백 제거
            return ssnMatcher.group(0).replace("-", "").replaceAll("\\s+", "");
        }
        return null;
    }


    // 생년월일 찾기 개선
    private String findDateOfBirth(String text) {
        // 주민등록번호에서 생년월일 추출
        String ssn = findSSN(text);
        if (ssn != null && ssn.length() == 13) {  // 주민등록번호가 올바르게 추출된 경우
            String year = ssn.substring(0, 2);
            String month = ssn.substring(2, 4);
            String day = ssn.substring(4, 6);

            // 2000년대생인지 1900년대생인지 결정 (기준은 현재 연도)
            int currentYear = Calendar.getInstance().get(Calendar.YEAR) % 100;
            int parsedYear = Integer.parseInt(year);
            String fullYear;
            if (parsedYear <= currentYear) {
                fullYear = "20" + year;
            } else {
                fullYear = "19" + year;
            }

            return fullYear + "년 " + Integer.parseInt(month) + "월 " + Integer.parseInt(day) + "일";
        }
        return null;
    }
    // 이름 찾기
    private String findName(String text) {
        // 주민등록번호 패턴을 찾아서 그 위의 텍스트를 찾음 (공백 또는 하이픈 포함 허용)
        Pattern ssnPattern = Pattern.compile("\\d{2}\\s?\\d{2}\\s?\\d{2}[-\\s]?\\d{7}");
        Matcher ssnMatcher = ssnPattern.matcher(text);

        if (ssnMatcher.find()) {
            // 주민등록번호 위치를 기준으로 그 위의 텍스트 추출
            int ssnStartIndex = ssnMatcher.start();
            String textBeforeSSN = text.substring(0, ssnStartIndex).trim();

            // 줄바꿈으로 텍스트 분리
            String[] lines = textBeforeSSN.split("\\n");

            // 주민등록번호 바로 위의 줄이 이름으로 간주
            if (lines.length > 0) {
                String possibleName = lines[lines.length - 1].trim(); // 주민등록번호 바로 위의 줄

                // 이름 후보에서 괄호 및 괄호 안의 문자 제거 후 한글만 남김
                possibleName = possibleName.replaceAll("\\([^)]*\\)", ""); // 괄호와 그 안의 내용 제거
                possibleName = possibleName.replaceAll("[^가-힣]", ""); // 한글만 남김

                if (!possibleName.isEmpty()) {
                    Log.d("OcrActivity", "추출된 이름: " + possibleName);
                    return possibleName;
                }
            }
        }
        Log.d("OcrActivity", "이름을 찾을 수 없음");
        return null;
    }



    // 신분증 발급일자 찾기 (주민등록증 및 운전면허증 구분)
    private String findIssueDate(String text) {
        // 모든 공백 제거
        text = text.replaceAll("\\s+", "");

        // 유연한 발급일자 패턴: 'yyyy mm dd' 형식, 중간 구분자는 '.' 또는 ',' 등 다양하게 허용
        Pattern generalIssueDatePattern = Pattern.compile("(\\d{4})[.,\\s]?(0?\\d{1,2})[.,\\s]?(0?\\d{1,2})");

        // 운전면허증의 발급일자 패턴: 'yyyy mm dd' 뒤에 발급 기관의 이름이 오는 경우
        Pattern licenseIssueDatePattern = Pattern.compile(
                "(\\d{4})[.,\\s]?(0?\\d{1,2})[.,\\s]?(0?\\d{1,2})([.,\\s]?)([가-힣]+청장|[가-힣]+관|[가-힣]+청|[가-힣]+소장|[가-힣]+구청장|[가-힣]+위원장)"
        );

        Matcher licenseMatcher = licenseIssueDatePattern.matcher(text);
        Matcher generalMatcher = generalIssueDatePattern.matcher(text);

        String issueDate = null;

        // 우선적으로 운전면허증 패턴 검사 (발급 기관의 이름이 있는 경우)
        if (licenseMatcher.find()) {
            issueDate = licenseMatcher.group(1) + "." + licenseMatcher.group(2) + "." + licenseMatcher.group(3);
        }
        // 운전면허증이 아니면 일반 발급일자 패턴 검사
        else if (generalMatcher.find()) {
            issueDate = generalMatcher.group(1) + "." + generalMatcher.group(2) + "." + generalMatcher.group(3);
        }

        // 발급일자가 추출된 경우, 'yyyy.MM.dd' 형식으로 변환
        if (issueDate != null) {
            issueDate = formatIssueDate(issueDate);
        } else {
            Log.d("OcrActivity", "발급일자 추출 실패");
        }

        return issueDate;
    }

    // 발급일자를 'yyyy.MM.dd' 형식으로 맞추는 메서드
    private String formatIssueDate(String issueDate) {
        try {
            // '.'을 기준으로 분리하여 각 부분을 두 자리로 맞춤
            String[] dateParts = issueDate.split("[.]");
            if (dateParts.length == 3) {
                String year = dateParts[0];
                String month = String.format("%02d", Integer.parseInt(dateParts[1]));
                String day = String.format("%02d", Integer.parseInt(dateParts[2]));
                return year + "." + month + "." + day;
            }
        } catch (NumberFormatException e) {
            Log.e("OcrActivity", "발급일자 형식 변환 실패", e);
        }
        return issueDate; // 기본적으로 입력받은 형식 그대로 반환
    }


    // 성인 여부 판단
    private boolean isMinor(String dob) {
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd", Locale.KOREA);
            // 생년월일에서 숫자만 추출하여 파싱
            Date birthDate = sdf.parse(dob.replaceAll("[^0-9]", ""));
            Calendar adultBoundary = Calendar.getInstance();
            adultBoundary.add(Calendar.YEAR, -19);
            return birthDate.after(adultBoundary.getTime());
        } catch (ParseException e) {
            Log.e("OcrActivity", "생년월일 파싱 실패", e);
            return true; // 파싱 실패 시 기본적으로 미성년자로 간주
        }
    }

    // 발급일자 비교 메서드
    private void isIssueDateMatch(String userId, String issueDate, IssueDateCallback callback) {
        FirebaseFirestore db = FirebaseFirestore.getInstance();
        DocumentReference userRef = db.collection("users").document(userId);

        userRef.get().addOnSuccessListener(documentSnapshot -> {
            if (documentSnapshot.exists()) {
                String userIssueDate = documentSnapshot.getString("issueDate");
                // 사용자가 가져온 발급일자와 비교
                boolean isMatch = userIssueDate != null && userIssueDate.equals(issueDate);
                callback.onCallback(isMatch);
            } else {
                callback.onCallback(false);
            }
        }).addOnFailureListener(e -> {
            Log.e("OcrActivity", "사용자 발급일자 가져오기 실패", e);
            callback.onCallback(false);
        });
    }

    // 발급일자 비교 콜백 인터페이스
    private interface IssueDateCallback {
        void onCallback(boolean isMatch);
    }
}