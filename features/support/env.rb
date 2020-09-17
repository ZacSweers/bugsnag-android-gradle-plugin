# Configure app environment

# Set which test fixture should be used
ENV["APP_FIXTURE_DIR"] ||= "features/fixtures/app"
ENV["NDK_FIXTURE_DIR"] ||= "features/fixtures/ndkapp"
ENV["RN_FIXTURE_DIR"] ||= "features/fixtures/rnapp/android"

# set defaults for versions
ENV["AGP_VERSION"] ||= "4.1.0-beta04" # default to latest
ENV["GRADLE_WRAPPER_VERSION"] ||= "6.5.1"

`./features/scripts/clear_local_maven_repo.sh`
`./features/scripts/setup_gradle_wrapper.sh`
`./features/scripts/install_gradle_plugin.sh`

Before('@requires_agp4_0_or_higher') do |scenario|
  skip_this_scenario unless is_above_or_equal_to_target(400)
end

Before('@requires_agp4_1_or_higher') do |scenario|
  skip_this_scenario unless is_above_or_equal_to_target(410)
end

Before('@skip_agp4_0_or_higher') do |scenario|
  skip_this_scenario if is_above_or_equal_to_target(400)
end

Before('@skip_agp4_1_or_higher') do |scenario|
  skip_this_scenario if is_above_or_equal_to_target(410)
end

def equals_target(target)
  version = ENV["AGP_VERSION"].slice(0, 5)
  version = version.gsub(".", "")
  return version.to_i == target
end

def is_above_or_equal_to_target(target)
  version = ENV["AGP_VERSION"].slice(0, 5)
  version = version.gsub(".", "")
  return version.to_i >= target
end

def get_build_requests
  Server.stored_requests.reject do |request|
    value = read_key_path(request[:body], 'appVersionCode')
    value.nil?
  end
end

def get_android_mapping_requests
  Server.stored_requests.reject do |request|
    value = read_key_path(request[:body], 'proguard')
    value.nil?
  end
end

def get_android_ndk_mapping_requests
  Server.stored_requests.reject do |request|
    value = read_key_path(request[:body], 'soSymbolFile')
    value.nil?
  end
end

$api_key = "TEST_API_KEY"
