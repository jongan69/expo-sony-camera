Pod::Spec.new do |s|
  s.name           = 'SonyCamera'
  s.version        = '0.2.1'
  s.summary        = 'Expo native module for Sony camera live view and remote capture'
  s.description    = 'ImageCaptureCore bridge for Sony camera live view and remote still capture.'
  s.author         = 'Jonathan Gan'
  s.homepage       = 'https://github.com/jongan69'
  s.platform      = :ios, '16.4'
  s.source         = { git: '' }
  s.static_framework = true

  s.dependency 'ExpoModulesCore'
  s.frameworks = 'AVFoundation', 'CoreImage', 'ImageCaptureCore', 'UIKit'

  # Swift/Objective-C compatibility
  s.pod_target_xcconfig = {
    'DEFINES_MODULE' => 'YES',
  }

  s.source_files = "**/*.{h,m,mm,swift,hpp,cpp}"
end
